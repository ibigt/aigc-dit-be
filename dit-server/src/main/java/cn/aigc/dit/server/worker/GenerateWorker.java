package cn.aigc.dit.server.worker;

import cn.aigc.dit.common.constant.CreditReason;
import cn.aigc.dit.common.constant.TaskStatus;
import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.generate.GenerateProvider;
import cn.aigc.dit.generate.GenerateProviderRegistry;
import cn.aigc.dit.generate.GenerateRequest;
import cn.aigc.dit.generate.PollResult;
import cn.aigc.dit.generate.SubmitResult;
import cn.aigc.dit.generate.poster.PosterExtractor;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.entity.Creation;
import cn.aigc.dit.server.entity.CreationSession;
import cn.aigc.dit.server.entity.GenTask;
import cn.aigc.dit.server.generate.GenerateRequests;
import cn.aigc.dit.server.mapper.AiModelMapper;
import cn.aigc.dit.server.mapper.CreationMapper;
import cn.aigc.dit.server.mapper.CreationSessionMapper;
import cn.aigc.dit.server.mapper.GenTaskMapper;
import cn.aigc.dit.server.service.CreditService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis Stream 消费者：只调 Canonical SPI，落封面与作品。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "dit.generate.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GenerateWorker {

    private final StringRedisTemplate stringRedisTemplate;
    private final GenerateProperties generateProperties;
    private final GenTaskMapper genTaskMapper;
    private final AiModelMapper aiModelMapper;
    private final CreationMapper creationMapper;
    private final CreationSessionMapper creationSessionMapper;
    private final GenerateProviderRegistry providerRegistry;
    private final PosterExtractor posterExtractor;
    private final CreditService creditService;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread workerThread;

    @PostConstruct
    void start() {
        workerThread = new Thread(this::loop, "dit-generate-worker");
        workerThread.setDaemon(true);
        workerThread.start();
        log.info("GenerateWorker listening on stream {}", generateProperties.getWorker().getStream());
    }

    @PreDestroy
    void stop() {
        running.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
        }
    }

    @Scheduled(fixedDelay = 15000)
    public void enqueuePending() {
        List<GenTask> pending = genTaskMapper.selectList(new LambdaQueryWrapper<GenTask>()
                .eq(GenTask::getStatus, TaskStatus.PENDING)
                .isNull(GenTask::getProviderJobId)
                .lt(GenTask::getCreatedAt, OffsetDateTime.now().minusSeconds(8))
                .last("LIMIT 20"));
        for (GenTask task : pending) {
            try {
                stringRedisTemplate.opsForStream().add(
                        generateProperties.getWorker().getStream(),
                        Map.of("taskId", String.valueOf(task.getId())));
            } catch (Exception e) {
                log.warn("re-enqueue pending task {} failed: {}", task.getId(), e.getMessage());
            }
        }
    }

    private void loop() {
        String stream = generateProperties.getWorker().getStream();
        String group = generateProperties.getWorker().getGroup();
        String consumerName = "worker-" + UUID.randomUUID();
        ensureGroup(stream, group);
        while (running.get()) {
            try {
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                        Consumer.from(group, consumerName),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(3)),
                        StreamOffset.create(stream, ReadOffset.lastConsumed()));
                if (records == null || records.isEmpty()) {
                    continue;
                }
                for (MapRecord<String, Object, Object> record : records) {
                    try {
                        process(record.getValue());
                        stringRedisTemplate.opsForStream().acknowledge(stream, group, record.getId());
                    } catch (Exception e) {
                        log.error("generate worker failed, id={}", record.getId(), e);
                        stringRedisTemplate.opsForStream().acknowledge(stream, group, record.getId());
                    }
                }
            } catch (Exception e) {
                if (running.get()) {
                    log.warn("generate stream read: {}", e.getMessage());
                    sleep(2000);
                    ensureGroup(stream, group);
                }
            }
        }
    }

    private void process(Map<Object, Object> payload) {
        Object rawId = payload.get("taskId");
        if (rawId == null) {
            return;
        }
        Long taskId = Long.valueOf(String.valueOf(rawId));
        GenTask task = genTaskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        if (TaskStatus.SUCCEEDED.equals(task.getStatus())
                || TaskStatus.FAILED.equals(task.getStatus())
                || TaskStatus.CANCELLED.equals(task.getStatus())) {
            return;
        }
        if (TaskStatus.RUNNING.equals(task.getStatus()) && task.getProviderJobId() != null) {
            return;
        }
        AiModel model = task.getModelId() == null ? null : aiModelMapper.selectById(task.getModelId());
        String modelKey = model == null ? null : model.getModelKey();
        GenerateRequest request = GenerateRequests.fromTask(task, modelKey);
        GenerateProvider provider = providerRegistry.resolve(model == null ? null : model.getProvider());
        task.setStatus(TaskStatus.RUNNING);
        task.setProgress(5);
        genTaskMapper.updateById(task);
        try {
            SubmitResult submitted = provider.submit(request);
            task.setProviderJobId(submitted.getJobId());
            genTaskMapper.updateById(task);
            PollResult result = pollUntilDone(provider, submitted.getJobId());
            if (result.isSucceeded()) {
                finishSuccess(task, request, result);
            } else {
                finishFailed(task, result.getErrorMessage() == null ? "生成失败" : result.getErrorMessage());
            }
        } catch (Exception e) {
            log.error("generate task {} failed", taskId, e);
            finishFailed(task, e.getMessage() == null ? "生成失败" : e.getMessage());
        }
    }

    private PollResult pollUntilDone(GenerateProvider provider, String jobId) throws InterruptedException {
        int timeoutMs = generateProperties.getWorker().getTimeoutSeconds() * 1000;
        int interval = Math.max(200, generateProperties.getWorker().getPollIntervalMs());
        long start = System.currentTimeMillis();
        PollResult last = PollResult.running(jobId, 10);
        while (System.currentTimeMillis() - start < timeoutMs) {
            last = provider.poll(jobId);
            updateProgress(jobId, last);
            if (last.isTerminal()) {
                return last;
            }
            Thread.sleep(interval);
        }
        return PollResult.failed(jobId, "生成超时");
    }

    private void updateProgress(String jobId, PollResult result) {
        GenTask task = genTaskMapper.selectOne(new LambdaQueryWrapper<GenTask>()
                .eq(GenTask::getProviderJobId, jobId)
                .last("LIMIT 1"));
        if (task == null || TaskStatus.SUCCEEDED.equals(task.getStatus()) || TaskStatus.FAILED.equals(task.getStatus())) {
            return;
        }
        task.setProgress(Math.max(task.getProgress() == null ? 0 : task.getProgress(), result.getProgress()));
        task.setStatus(TaskStatus.RUNNING);
        genTaskMapper.updateById(task);
    }

    private void finishSuccess(GenTask task, GenerateRequest request, PollResult result) {
        List<String> keys = result.getOutputKeys() == null || result.getOutputKeys().isEmpty()
                ? (result.getOutputStorageKey() == null ? List.of() : List.of(result.getOutputStorageKey()))
                : result.getOutputKeys();
        if (keys.isEmpty()) {
            finishFailed(task, "Mock 未返回成片");
            return;
        }
        String firstPoster = null;
        Long sessionId = sessionId(task);
        CreationSession session = sessionId == null ? null : creationSessionMapper.selectById(sessionId);
        if (session == null) {
            sessionId = null;
        }
        long perCopy = Math.max(0, (task.getCreditCost() == null ? 0 : task.getCreditCost()) / Math.max(1, keys.size()));
        int index = 1;
        for (String key : keys) {
            String poster = posterExtractor.extract(key);
            if (firstPoster == null) {
                firstPoster = poster;
            }
            Creation creation = new Creation();
            creation.setSessionId(sessionId);
            creation.setUserId(task.getUserId());
            creation.setTaskId(task.getId());
            creation.setModelId(task.getModelId());
            creation.setTitle(titleOf(request.getPrompt()));
            creation.setCategory(task.getCategory());
            creation.setStorageKey(key);
            creation.setPosterKey(poster);
            creation.setPrompt(task.getPrompt());
            creation.setRatio(request.getRatio());
            creation.setResolution(request.getResolution());
            creation.setDurationSec(request.getDurationSec());
            creation.setCreditCost(perCopy);
            creation.setCopyIndex(index++);
            creationMapper.insert(creation);
        }
        if (session != null) {
            creationSessionMapper.updateById(session);
        }
        task.setOutputStorageKey(keys.get(0));
        task.setOutputKeys(keys);
        task.setPosterKey(firstPoster);
        task.setStatus(TaskStatus.SUCCEEDED);
        task.setProgress(100);
        task.setFinishedAt(OffsetDateTime.now());
        task.setErrorMessage(null);
        genTaskMapper.updateById(task);
    }

    private void finishFailed(GenTask task, String message) {
        task.setStatus(TaskStatus.FAILED);
        task.setErrorMessage(message);
        task.setFinishedAt(OffsetDateTime.now());
        genTaskMapper.updateById(task);
        if (task.getCreditCost() != null && task.getCreditCost() > 0) {
            try {
                creditService.change(task.getUserId(), task.getCreditCost(), CreditReason.REFUND,
                        String.valueOf(task.getId()), "生成失败退还积分");
            } catch (Exception e) {
                log.error("refund credits failed for task {}", task.getId(), e);
            }
        }
    }

    private void ensureGroup(String stream, String group) {
        try {
            stringRedisTemplate.opsForStream().createGroup(stream, ReadOffset.from("0-0"), group);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (!msg.contains("BUSYGROUP")) {
                log.debug("create group {}: {}", group, msg);
            }
        }
    }

    private static Long sessionId(GenTask task) {
        if (task.getParams() == null || task.getParams().get("sessionId") == null) {
            return null;
        }
        Object value = task.getParams().get("sessionId");
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String titleOf(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "未命名作品";
        }
        String text = prompt.trim();
        return text.length() > 24 ? text.substring(0, 24) : text;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
