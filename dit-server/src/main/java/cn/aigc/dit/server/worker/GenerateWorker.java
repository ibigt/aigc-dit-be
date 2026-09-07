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
import cn.aigc.dit.generate.runninghub.RunningHubGenerateProvider;
import cn.aigc.dit.generate.runninghub.RunningHubUrls;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.entity.Creation;
import cn.aigc.dit.server.entity.CreationSession;
import cn.aigc.dit.server.entity.GenTask;
import cn.aigc.dit.server.generate.GenerateRequests;
import cn.aigc.dit.server.crypto.AesGcmEncryptor;
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
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    private final AesGcmEncryptor encryptor;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final ConcurrentHashMap<Long, Object> taskLocks = new ConcurrentHashMap<>();
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

    @Scheduled(fixedDelay = 15000)
    public void resumeStaleJobs() {
        OffsetDateTime staleBefore = OffsetDateTime.now().minusSeconds(20);
        List<GenTask> runningTasks = genTaskMapper.selectList(new LambdaQueryWrapper<GenTask>()
                .eq(GenTask::getStatus, TaskStatus.RUNNING)
                .isNotNull(GenTask::getProviderJobId)
                .gt(GenTask::getCreatedAt, OffsetDateTime.now().minusHours(24))
                .lt(GenTask::getUpdatedAt, staleBefore)
                .last("LIMIT 10"));
        List<GenTask> timedOut = genTaskMapper.selectList(new LambdaQueryWrapper<GenTask>()
                .eq(GenTask::getStatus, TaskStatus.FAILED)
                .isNotNull(GenTask::getProviderJobId)
                .gt(GenTask::getCreatedAt, OffsetDateTime.now().minusHours(24))
                .and(w -> w.eq(GenTask::getErrorMessage, "生成超时")
                        .or().like(GenTask::getErrorMessage, "下载 RunningHub")
                        .or().like(GenTask::getErrorMessage, "成片回收")
                        .or().like(GenTask::getErrorMessage, "成片为空")
                        .or().like(GenTask::getErrorMessage, "成片 URL"))
                .last("LIMIT 10"));
        List<GenTask> toResume = new ArrayList<>(runningTasks);
        toResume.addAll(timedOut);
        for (GenTask task : toResume) {
            try {
                resumeOne(task);
            } catch (Exception e) {
                log.warn("resume generate task {} failed: {}", task.getId(), e.getMessage());
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
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(5)),
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
                if (!running.get()) {
                    break;
                }
                if (isIdleReadTimeout(e)) {
                    continue;
                }
                log.warn("generate stream read: {}", e.getMessage());
                sleep(2000);
                ensureGroup(stream, group);
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
        bindProvider(request, model);
        GenerateProvider provider = providerRegistry.resolve(model == null ? null : model.getProvider());
        task.setStatus(TaskStatus.RUNNING);
        task.setProgress(5);
        genTaskMapper.updateById(task);
        try {
            SubmitResult submitted = provider.submit(request);
            task.setProviderJobId(submitted.getJobId());
            genTaskMapper.updateById(task);
            PollResult result = pollUntilDone(provider, submitted.getJobId());
            applyPollResult(task, request, result);
        } catch (Exception e) {
            log.error("generate task {} failed", taskId, e);
            finishFailed(task, e.getMessage() == null ? "生成失败" : e.getMessage());
        }
    }

    private void resumeOne(GenTask task) {
        synchronized (lockFor(task.getId())) {
            GenTask latest = genTaskMapper.selectById(task.getId());
            if (latest == null || !StringUtils.hasText(latest.getProviderJobId())) {
                return;
            }
            if (TaskStatus.SUCCEEDED.equals(latest.getStatus()) || TaskStatus.CANCELLED.equals(latest.getStatus())) {
                return;
            }
            if (hasCreations(latest.getId())) {
                return;
            }
            boolean recoverableFail = TaskStatus.FAILED.equals(latest.getStatus());
            AiModel model = latest.getModelId() == null ? null : aiModelMapper.selectById(latest.getModelId());
            String modelKey = model == null ? null : model.getModelKey();
            GenerateRequest request = GenerateRequests.fromTask(latest, modelKey);
            bindProvider(request, model);
            GenerateProvider provider = providerRegistry.resolve(model == null ? null : model.getProvider());
            rememberProviderJob(provider, latest, request, model);
            if (recoverableFail) {
                latest.setStatus(TaskStatus.RUNNING);
                latest.setErrorMessage(null);
                latest.setFinishedAt(null);
                latest.setProgress(Math.max(latest.getProgress() == null ? 10 : latest.getProgress(), 55));
                genTaskMapper.updateById(latest);
            }
            PollResult result = provider.poll(latest.getProviderJobId());
            if (result.isSucceeded()) {
                log.info("recovered generate task {} from RunningHub job {}", latest.getId(), latest.getProviderJobId());
                finishSuccess(latest, request, result);
                return;
            }
            if (result.isTerminal() && !isRecoverableIngestError(result.getErrorMessage())) {
                finishFailed(latest, result.getErrorMessage() == null ? "生成失败" : result.getErrorMessage());
                return;
            }
            updateProgress(latest.getProviderJobId(), result);
            if (latest.getCreatedAt() != null && latest.getCreatedAt().isBefore(OffsetDateTime.now().minusHours(4))) {
                finishFailed(latest, "生成超时");
            }
        }
    }

    private void applyPollResult(GenTask task, GenerateRequest request, PollResult result) {
        if (result.isSucceeded()) {
            finishSuccess(task, request, result);
        } else if (result.isTerminal() && !isRecoverableIngestError(result.getErrorMessage())) {
            finishFailed(task, result.getErrorMessage() == null ? "生成失败" : result.getErrorMessage());
        } else {
            log.info("generate task {} still running on provider job {}, will resume later",
                    task.getId(), result.getJobId());
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
        last = provider.poll(jobId);
        updateProgress(jobId, last);
        if (last.isTerminal()) {
            return last;
        }
        log.warn("generate job {} still running after {}s, leave task open for resume", jobId, timeoutMs / 1000);
        return last;
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
        synchronized (lockFor(task.getId())) {
            GenTask latest = genTaskMapper.selectById(task.getId());
            if (latest == null || TaskStatus.SUCCEEDED.equals(latest.getStatus()) || hasCreations(latest.getId())) {
                return;
            }
            List<String> keys = result.getOutputKeys() == null || result.getOutputKeys().isEmpty()
                    ? (result.getOutputStorageKey() == null ? List.of() : List.of(result.getOutputStorageKey()))
                    : result.getOutputKeys();
            if (keys.isEmpty()) {
                finishFailed(latest, "未返回成片");
                return;
            }
            String firstPoster = null;
            Long sessionId = sessionId(latest);
            CreationSession session = sessionId == null ? null : creationSessionMapper.selectById(sessionId);
            if (session == null) {
                sessionId = null;
            }
            long perCopy = Math.max(0, (latest.getCreditCost() == null ? 0 : latest.getCreditCost()) / Math.max(1, keys.size()));
            int index = 1;
            for (String key : keys) {
                String poster = posterExtractor.extract(key);
                if (firstPoster == null) {
                    firstPoster = poster;
                }
                Creation creation = new Creation();
                creation.setSessionId(sessionId);
                creation.setUserId(latest.getUserId());
                creation.setTaskId(latest.getId());
                creation.setModelId(latest.getModelId());
                creation.setTitle(titleOf(request.getPrompt()));
                creation.setCategory(latest.getCategory());
                creation.setStorageKey(key);
                creation.setPosterKey(poster);
                creation.setPrompt(latest.getPrompt());
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
            latest.setOutputStorageKey(keys.get(0));
            latest.setOutputKeys(keys);
            latest.setPosterKey(firstPoster);
            latest.setStatus(TaskStatus.SUCCEEDED);
            latest.setProgress(100);
            latest.setFinishedAt(OffsetDateTime.now());
            latest.setErrorMessage(null);
            genTaskMapper.updateById(latest);
        }
    }

    private void finishFailed(GenTask task, String message) {
        synchronized (lockFor(task.getId())) {
            GenTask latest = genTaskMapper.selectById(task.getId());
            if (latest == null || TaskStatus.SUCCEEDED.equals(latest.getStatus()) || TaskStatus.FAILED.equals(latest.getStatus())) {
                return;
            }
            latest.setStatus(TaskStatus.FAILED);
            latest.setErrorMessage(message);
            latest.setFinishedAt(OffsetDateTime.now());
            genTaskMapper.updateById(latest);
            if (latest.getCreditCost() != null && latest.getCreditCost() > 0) {
                try {
                    creditService.change(latest.getUserId(), latest.getCreditCost(), CreditReason.REFUND,
                            String.valueOf(latest.getId()), "生成失败退还积分");
                } catch (Exception e) {
                    log.error("refund credits failed for task {}", latest.getId(), e);
                }
            }
        }
    }

    private void rememberProviderJob(GenerateProvider provider, GenTask task, GenerateRequest request, AiModel model) {
        if (!(provider instanceof RunningHubGenerateProvider runningHub) || !StringUtils.hasText(task.getProviderJobId())) {
            return;
        }
        String openApiBase = RunningHubUrls.openApiBase(request.getEndpoint(), generateProperties.getRunninghub().getBaseUrl());
        runningHub.remember(task.getProviderJobId(), request, openApiBase, resolveApiKey(model));
    }

    private boolean hasCreations(Long taskId) {
        return creationMapper.selectCount(new LambdaQueryWrapper<Creation>().eq(Creation::getTaskId, taskId)) > 0;
    }

    private Object lockFor(Long taskId) {
        return taskLocks.computeIfAbsent(taskId, id -> new Object());
    }

    static boolean isRecoverableIngestError(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return message.contains("下载 RunningHub")
                || message.contains("成片回收")
                || message.contains("成片为空")
                || message.contains("成片 URL");
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

    private void bindProvider(GenerateRequest request, AiModel model) {
        if (model == null) {
            return;
        }
        request.setEndpoint(model.getEndpoint());
        request.setAdapterConfig(model.getAdapterConfig());
        request.setApiKey(resolveApiKey(model));
    }

    private String resolveApiKey(AiModel model) {
        if (StringUtils.hasText(model.getApiKeyEnc())) {
            String decrypted = encryptor.decrypt(model.getApiKeyEnc());
            if (StringUtils.hasText(decrypted)) {
                return decrypted.trim();
            }
        }
        return generateProperties.getRunninghub().getApiKey();
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

    private static boolean isIdleReadTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String name = current.getClass().getSimpleName();
            String message = current.getMessage() == null ? "" : current.getMessage();
            if (name.contains("Timeout") || message.contains("timed out") || message.contains("Timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
