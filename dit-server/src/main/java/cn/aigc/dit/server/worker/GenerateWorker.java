package cn.aigc.dit.server.worker;

import cn.aigc.dit.common.constant.CreditReason;
import cn.aigc.dit.common.constant.TaskStatus;
import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.generate.GenerateProvider;
import cn.aigc.dit.generate.GenerateProviderRegistry;
import cn.aigc.dit.generate.GenerateRequest;
import cn.aigc.dit.generate.PollResult;
import cn.aigc.dit.generate.SubmitResult;
import cn.aigc.dit.generate.poster.PosterExtractor;
import cn.aigc.dit.generate.agnes.AgnesGenerateProvider;
import cn.aigc.dit.generate.agnes.AgnesUrls;
import cn.aigc.dit.generate.ark.ArkGenerateProvider;
import cn.aigc.dit.generate.ark.ArkKeys;
import cn.aigc.dit.generate.ark.ArkUrls;
import cn.aigc.dit.generate.comfyui.ComfyUiGenerateProvider;
import cn.aigc.dit.generate.minimax.MiniMaxGenerateProvider;
import cn.aigc.dit.generate.minimax.MiniMaxSpeechProvider;
import cn.aigc.dit.generate.minimax.MiniMaxUrls;
import cn.aigc.dit.generate.runninghub.RunningHubGenerateProvider;
import cn.aigc.dit.generate.runninghub.RunningHubUrls;
import cn.aigc.dit.generate.speech.OpenAiSpeechProvider;
import cn.aigc.dit.generate.openaiimage.OpenAiImageGenerateProvider;
import cn.aigc.dit.generate.seedream.SeedreamGenerateProvider;
import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.entity.Creation;
import cn.aigc.dit.server.entity.CreationSession;
import cn.aigc.dit.server.entity.GenTask;
import cn.aigc.dit.server.drama.DramaContract;
import cn.aigc.dit.server.drama.DramaShotCaptionService;
import cn.aigc.dit.server.generate.GenerateRequests;
import cn.aigc.dit.server.generate.GenerateTaskViews;
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
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
    /** 分镜出片烧字幕已暂停，成片阶段再接回 {@code burnIfNeeded}。 */
    @SuppressWarnings("unused")
    private final DramaShotCaptionService dramaShotCaptionService;
    private final CreditService creditService;
    private final AesGcmEncryptor encryptor;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final ConcurrentHashMap<Long, Object> taskLocks = new ConcurrentHashMap<>();
    private final Object dramaShotSlots = new Object();
    private final AtomicBoolean arkSubmitting = new AtomicBoolean(false);
    private volatile long arkBusyUntilMs;
    private final AtomicLong lastHeartbeatMs = new AtomicLong(0);
    private Thread workerThread;

    @PostConstruct
    void start() {
        touchHeartbeat();
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

    @Scheduled(fixedDelay = 5000)
    public void enqueuePending() {
        touchHeartbeat();
        OffsetDateTime since = OffsetDateTime.now().minusHours(24);
        OffsetDateTime staleBefore = OffsetDateTime.now().minusSeconds(30);
        List<GenTask> pending = new ArrayList<>(genTaskMapper.selectList(new LambdaQueryWrapper<GenTask>()
                .eq(GenTask::getStatus, TaskStatus.PENDING)
                .isNull(GenTask::getProviderJobId)
                .gt(GenTask::getCreatedAt, since)
                .orderByAsc(GenTask::getCreatedAt)
                .last("LIMIT 50")));
        pending.addAll(genTaskMapper.selectList(new LambdaQueryWrapper<GenTask>()
                .eq(GenTask::getStatus, TaskStatus.RUNNING)
                .isNull(GenTask::getProviderJobId)
                .gt(GenTask::getCreatedAt, since)
                .lt(GenTask::getUpdatedAt, staleBefore)
                .last("LIMIT 20")));
        if (!pending.isEmpty()) {
            log.info("submit pending generate tasks: {}", pending.stream().map(GenTask::getId).toList());
        }
        boolean arkSubmitStarted = false;
        for (GenTask task : pending) {
            if (shouldDelayRetry(task)) {
                continue;
            }
            if (isArkTask(task)) {
                if (arkSubmitStarted || System.currentTimeMillis() < arkBusyUntilMs) {
                    continue;
                }
                arkSubmitStarted = true;
            }
            try {
                process(Map.of("taskId", String.valueOf(task.getId())));
            } catch (Exception e) {
                log.warn("submit pending task {} failed: {}", task.getId(), e.getMessage());
            }
        }
    }

    @Scheduled(fixedDelay = 5000)
    public void resumeStaleJobs() {
        touchHeartbeat();
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
                        .or().like(GenTask::getErrorMessage, "下载 Agnes")
                        .or().like(GenTask::getErrorMessage, "下载 MiniMax")
                        .or().like(GenTask::getErrorMessage, "下载 Seedance")
                        .or().like(GenTask::getErrorMessage, "成片回收")
                        .or().like(GenTask::getErrorMessage, "成片为空")
                        .or().like(GenTask::getErrorMessage, "成片 URL"))
                .last("LIMIT 10"));
        List<GenTask> foreignFailed = genTaskMapper.selectList(new LambdaQueryWrapper<GenTask>()
                .eq(GenTask::getStatus, TaskStatus.FAILED)
                .isNotNull(GenTask::getProviderJobId)
                .gt(GenTask::getCreatedAt, OffsetDateTime.now().minusHours(4))
                .like(GenTask::getErrorMessage, "Agnes")
                .last("LIMIT 10"));
        List<GenTask> toResume = new ArrayList<>(runningTasks);
        toResume.addAll(timedOut);
        for (GenTask task : foreignFailed) {
            if (isOfficialSeedanceTask(task)) {
                log.warn("recover official seedance task {} killed by foreign Agnes error: {}",
                        task.getId(), task.getErrorMessage());
                toResume.add(task);
            }
        }
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
        String consumerName = "dit-generate-0";
        ensureGroup(stream, group);
        log.info("GenerateWorker consuming stream {} group {} as {}", stream, group, consumerName);
        while (running.get()) {
            touchHeartbeat();
            try {
                claimIdlePending(stream, group, consumerName);
                List<MapRecord<String, Object, Object>> records = readStream(stream, group, consumerName);
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

    private List<MapRecord<String, Object, Object>> readStream(String stream, String group, String consumerName) {
        Consumer consumer = Consumer.from(group, consumerName);
        List<MapRecord<String, Object, Object>> pending = stringRedisTemplate.opsForStream().read(
                consumer,
                StreamReadOptions.empty().count(10),
                StreamOffset.create(stream, ReadOffset.from("0")));
        if (pending != null && !pending.isEmpty()) {
            return pending;
        }
        return stringRedisTemplate.opsForStream().read(
                consumer,
                StreamReadOptions.empty().count(10).block(Duration.ofSeconds(5)),
                StreamOffset.create(stream, ReadOffset.lastConsumed()));
    }

    public void kick(Long taskId) {
        if (taskId == null) {
            return;
        }
        try {
            process(Map.of("taskId", String.valueOf(taskId)));
        } catch (Exception e) {
            log.warn("kick generate task {} failed: {}", taskId, e.getMessage());
        }
    }

    public void withTaskLock(Long taskId, Runnable action) {
        if (taskId == null || action == null) {
            return;
        }
        synchronized (lockFor(taskId)) {
            action.run();
        }
    }

    public boolean heartbeatFresh() {
        long last = lastHeartbeatMs.get();
        return last > 0 && System.currentTimeMillis() - last <= 30_000L;
    }

    public void cancelProviderJob(GenTask task) {
        if (task == null || !StringUtils.hasText(task.getProviderJobId())) {
            return;
        }
        try {
            AiModel model = task.getModelId() == null ? null : aiModelMapper.selectById(task.getModelId());
            String modelKey = model == null ? null : model.getModelKey();
            GenerateRequest request = GenerateRequests.fromTask(task, modelKey);
            GenerateProvider provider = bindAndResolve(task, model, request);
            provider.cancel(task.getProviderJobId());
        } catch (Exception e) {
            log.warn("cancel provider job {} for task {} failed: {}",
                    task.getProviderJobId(), task.getId(), e.getMessage());
        }
    }

    void touchHeartbeat() {
        lastHeartbeatMs.set(System.currentTimeMillis());
    }

    private void process(Map<Object, Object> payload) {
        Long taskId = taskIdOf(payload);
        if (taskId == null) {
            log.warn("generate stream payload missing taskId: {}", payload == null ? null : payload.keySet());
            return;
        }
        synchronized (lockFor(taskId)) {
            processLocked(taskId);
        }
    }

    private void processLocked(Long taskId) {
        GenTask task = genTaskMapper.selectById(taskId);
        if (task == null) {
            log.warn("generate task {} not found", taskId);
            return;
        }
        log.info("processing generate task {} status={} modelId={}", taskId, task.getStatus(), task.getModelId());
        if (TaskStatus.SUCCEEDED.equals(task.getStatus())
                || TaskStatus.FAILED.equals(task.getStatus())
                || TaskStatus.CANCELLED.equals(task.getStatus())) {
            return;
        }
        if (StringUtils.hasText(task.getProviderJobId())) {
            return;
        }
        if (TaskStatus.RUNNING.equals(task.getStatus())
                && task.getUpdatedAt() != null
                && task.getUpdatedAt().isAfter(OffsetDateTime.now().minusSeconds(30))) {
            return;
        }
        if (shouldDelayRetry(task)) {
            return;
        }
        if (!tryAcquireDramaShotSlot(task)) {
            return;
        }
        boolean ark = isArkTask(task);
        if (ark) {
            long now = System.currentTimeMillis();
            if (now < arkBusyUntilMs || !arkSubmitting.compareAndSet(false, true)) {
                return;
            }
        }
        try {
            AiModel model = task.getModelId() == null ? null : aiModelMapper.selectById(task.getModelId());
            String modelKey = model == null ? null : model.getModelKey();
            GenerateRequest request = GenerateRequests.fromTask(task, modelKey);
            GenerateProvider provider = bindAndResolve(task, model, request);
            task.setStatus(TaskStatus.RUNNING);
            task.setProgress(5);
            if (task.getStartedAt() == null) {
                task.setStartedAt(OffsetDateTime.now());
            }
            genTaskMapper.updateById(task);
            SubmitResult submitted = provider.submit(request);
            task.setProviderJobId(submitted.getJobId());
            genTaskMapper.updateById(task);
            PollResult result = provider.poll(submitted.getJobId());
            updateProgress(submitted.getJobId(), result);
            applyPollResult(task, request, result);
        } catch (Exception e) {
            String message = e.getMessage() == null ? "生成失败" : e.getMessage();
            if (!StringUtils.hasText(task.getProviderJobId()) && isRetryableSubmitError(message)) {
                log.warn("generate task {} submit busy, will retry later: {}", taskId, message);
                deferSubmit(task, message);
                return;
            }
            log.error("generate task {} failed", taskId, e);
            finishFailed(task, message);
        } finally {
            if (ark) {
                arkSubmitting.set(false);
            }
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
            GenerateProvider provider = bindAndResolve(latest, model, request);
            rememberProviderJob(provider, latest, request, model);
            log.info("resume generate task {} via {} job {}", latest.getId(), provider.providerKey(), latest.getProviderJobId());
            if (recoverableFail) {
                latest.setStatus(TaskStatus.RUNNING);
                latest.setErrorMessage(null);
                latest.setFinishedAt(null);
                if (latest.getStartedAt() == null) {
                    latest.setStartedAt(OffsetDateTime.now());
                }
                latest.setProgress(Math.max(latest.getProgress() == null ? 10 : latest.getProgress(), 55));
                genTaskMapper.updateById(latest);
            }
            PollResult result = provider.poll(latest.getProviderJobId());
            log.info("resume generate task {} poll status={} error={}",
                    latest.getId(), result.getStatus(), result.getErrorMessage());
            if (result.isSucceeded()) {
                log.info("recovered generate task {} from provider job {}", latest.getId(), latest.getProviderJobId());
                finishSuccess(latest, request, result);
                return;
            }
            if (result.isTerminal()
                    && !isRecoverableIngestError(result.getErrorMessage())
                    && !isForeignProviderError(request.getAdapterKey(), result.getErrorMessage())) {
                finishFailed(latest, result.getErrorMessage() == null ? "生成失败" : result.getErrorMessage());
                return;
            }
            updateProgress(latest.getProviderJobId(), result);
            keepAliveIfStillRunning(latest, result);
            if (latest.getCreatedAt() != null && latest.getCreatedAt().isBefore(OffsetDateTime.now().minusHours(4))) {
                finishFailed(latest, "生成超时");
            }
        }
    }

    private void applyPollResult(GenTask task, GenerateRequest request, PollResult result) {
        if (result.isSucceeded()) {
            finishSuccess(task, request, result);
        } else if (result.isTerminal()
                && !isRecoverableIngestError(result.getErrorMessage())
                && !isForeignProviderError(request.getAdapterKey(), result.getErrorMessage())) {
            finishFailed(task, result.getErrorMessage() == null ? "生成失败" : result.getErrorMessage());
        } else {
            log.info("generate task {} still running on provider job {}, will resume later",
                    task.getId(), result.getJobId());
        }
    }

    private void updateProgress(String jobId, PollResult result) {
        GenTask task = genTaskMapper.selectOne(new LambdaQueryWrapper<GenTask>()
                .eq(GenTask::getProviderJobId, jobId)
                .last("LIMIT 1"));
        if (task == null || TaskStatus.SUCCEEDED.equals(task.getStatus()) || TaskStatus.CANCELLED.equals(task.getStatus())) {
            return;
        }
        if (TaskStatus.FAILED.equals(task.getStatus()) && !GenerateTaskViews.shouldKeepPolling(task)) {
            return;
        }
        task.setProgress(Math.max(task.getProgress() == null ? 0 : task.getProgress(), result.getProgress()));
        task.setStatus(TaskStatus.RUNNING);
        task.setFinishedAt(null);
        if (task.getStartedAt() == null) {
            task.setStartedAt(OffsetDateTime.now());
        }
        if (GenerateTaskViews.isForeignAgnesError(task.getErrorMessage())) {
            task.setErrorMessage(null);
        }
        genTaskMapper.updateById(task);
    }

    private void keepAliveIfStillRunning(GenTask task, PollResult result) {
        if (result == null || result.isTerminal() || result.isSucceeded()) {
            return;
        }
        GenTask latest = genTaskMapper.selectById(task.getId());
        if (latest == null
                || TaskStatus.SUCCEEDED.equals(latest.getStatus())
                || TaskStatus.CANCELLED.equals(latest.getStatus())) {
            return;
        }
        if (TaskStatus.FAILED.equals(latest.getStatus()) && !GenerateTaskViews.shouldKeepPolling(latest)) {
            return;
        }
        latest.setStatus(TaskStatus.RUNNING);
        latest.setFinishedAt(null);
        if (latest.getStartedAt() == null) {
            latest.setStartedAt(OffsetDateTime.now());
        }
        if (GenerateTaskViews.isForeignAgnesError(latest.getErrorMessage())) {
            latest.setErrorMessage(null);
        }
        if (result.getProgress() > 0) {
            latest.setProgress(Math.max(latest.getProgress() == null ? 0 : latest.getProgress(), result.getProgress()));
        }
        genTaskMapper.updateById(latest);
    }

    private void finishSuccess(GenTask task, GenerateRequest request, PollResult result) {
        boolean releaseDramaSlot = false;
        synchronized (lockFor(task.getId())) {
            GenTask latest = genTaskMapper.selectById(task.getId());
            if (latest == null
                    || TaskStatus.SUCCEEDED.equals(latest.getStatus())
                    || TaskStatus.CANCELLED.equals(latest.getStatus())
                    || hasCreations(latest.getId())) {
                return;
            }
            List<String> keys = result.getOutputKeys() == null || result.getOutputKeys().isEmpty()
                    ? (result.getOutputStorageKey() == null ? List.of() : List.of(result.getOutputStorageKey()))
                    : result.getOutputKeys();
            if (keys.isEmpty()) {
                finishFailed(latest, "未返回成片");
                return;
            }
            // 分镜出片暂不烧字幕，改到成片拼接阶段再打开。
            // if (GenerateRequests.isDramaShot(latest.getParams())) {
            //     keys = dramaShotCaptionService.burnIfNeeded(latest, keys);
            // }
            String firstPoster = null;
            String firstTail = null;
            boolean recordCreation = GenerateRequests.bindsQuickCreate(latest.getParams());
            Long sessionId = recordCreation ? sessionId(latest) : null;
            CreationSession session = sessionId == null ? null : creationSessionMapper.selectById(sessionId);
            if (session == null) {
                sessionId = null;
            }
            long perCopy = Math.max(0, (latest.getCreditCost() == null ? 0 : latest.getCreditCost()) / Math.max(1, keys.size()));
            int index = 1;
            boolean dramaShot = GenerateRequests.isDramaShot(latest.getParams());
            for (String key : keys) {
                PosterExtractor.VideoStills stills;
                if (ModelCategory.AUDIO.equalsIgnoreCase(latest.getCategory())) {
                    stills = new PosterExtractor.VideoStills(null, null);
                } else if (dramaShot) {
                    stills = posterExtractor.extractStills(key);
                } else {
                    stills = new PosterExtractor.VideoStills(posterExtractor.extract(key), null);
                }
                String poster = stills.posterKey();
                if (firstPoster == null) {
                    firstPoster = poster;
                    firstTail = stills.tailKey();
                }
                if (!recordCreation) {
                    continue;
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
            if (recordCreation && session != null) {
                creationSessionMapper.updateById(session);
            }
            latest.setOutputStorageKey(keys.get(0));
            latest.setOutputKeys(keys);
            latest.setPosterKey(firstPoster);
            if (StringUtils.hasText(firstTail)) {
                java.util.Map<String, Object> params = latest.getParams() == null
                        ? new java.util.LinkedHashMap<>()
                        : new java.util.LinkedHashMap<>(latest.getParams());
                params.put("tailKey", firstTail);
                latest.setParams(params);
            }
            latest.setStatus(TaskStatus.SUCCEEDED);
            latest.setProgress(100);
            latest.setFinishedAt(OffsetDateTime.now());
            latest.setErrorMessage(null);
            genTaskMapper.updateById(latest);
            releaseDramaSlot = GenerateRequests.isDramaShot(latest.getParams());
        }
        if (releaseDramaSlot) {
            kickDramaShotQueue();
        }
    }

    private void finishFailed(GenTask task, String message) {
        boolean releaseDramaSlot = false;
        synchronized (lockFor(task.getId())) {
            GenTask latest = genTaskMapper.selectById(task.getId());
            if (latest == null || TaskStatus.SUCCEEDED.equals(latest.getStatus()) || TaskStatus.CANCELLED.equals(latest.getStatus())) {
                return;
            }
            if (shouldIgnoreForeignFailure(latest, message)) {
                log.warn("ignore foreign provider error on generate task {}: {}", latest.getId(), message);
                return;
            }
            String mapped = GenerateTaskViews.friendlySeedanceError(message);
            boolean alreadyFailed = TaskStatus.FAILED.equals(latest.getStatus());
            if (alreadyFailed) {
                if (!GenerateTaskViews.isForeignAgnesError(latest.getErrorMessage())) {
                    return;
                }
                log.warn("replace foreign Agnes error on generate task {} with official error", latest.getId());
            }
            storeOfficialError(latest, mapped);
            latest.setStatus(TaskStatus.FAILED);
            latest.setErrorMessage(mapped);
            latest.setFinishedAt(OffsetDateTime.now());
            genTaskMapper.updateById(latest);
            releaseDramaSlot = GenerateRequests.isDramaShot(latest.getParams());
            // 供应商已接受任务后即可能产生真实成本，失败也不能由平台退款兜底。
            // 仅在尚未取得供应商任务号时退还预扣积分。
            if (!alreadyFailed && !StringUtils.hasText(latest.getProviderJobId())
                    && latest.getCreditCost() != null && latest.getCreditCost() > 0) {
                try {
                    creditService.change(latest.getUserId(), latest.getCreditCost(), CreditReason.REFUND,
                            String.valueOf(latest.getId()), "生成失败退还积分");
                } catch (Exception e) {
                    log.error("refund credits failed for task {}", latest.getId(), e);
                }
            }
        }
        if (releaseDramaSlot) {
            kickDramaShotQueue();
        }
    }

    private boolean tryAcquireDramaShotSlot(GenTask task) {
        if (!GenerateRequests.isDramaShot(task.getParams())) {
            return true;
        }
        synchronized (dramaShotSlots) {
            long running = countActiveDramaShotTasks();
            if (running >= DramaContract.MAX_SHOT_GENERATE) {
                if (!TaskStatus.PENDING.equals(task.getStatus()) || StringUtils.hasText(task.getProviderJobId())) {
                    task.setStatus(TaskStatus.PENDING);
                    task.setProviderJobId(null);
                    genTaskMapper.updateById(task);
                }
                log.info("generate task {} queued, {}/{} drama shots running",
                        task.getId(), running, DramaContract.MAX_SHOT_GENERATE);
                return false;
            }
            return true;
        }
    }

    private long countActiveDramaShotTasks() {
        Long count = genTaskMapper.selectCount(new LambdaQueryWrapper<GenTask>()
                .eq(GenTask::getStatus, TaskStatus.RUNNING)
                .apply("params->>'source' = {0}", GenerateRequests.SOURCE_DRAMA)
                .apply("params->>'shotId' is not null"));
        return count == null ? 0 : count;
    }

    private void kickDramaShotQueue() {
        List<GenTask> next = genTaskMapper.selectList(new LambdaQueryWrapper<GenTask>()
                .eq(GenTask::getStatus, TaskStatus.PENDING)
                .isNull(GenTask::getProviderJobId)
                .apply("params->>'source' = {0}", GenerateRequests.SOURCE_DRAMA)
                .apply("params->>'shotId' is not null")
                .orderByAsc(GenTask::getCreatedAt)
                .last("LIMIT 1"));
        if (next.isEmpty()) {
            return;
        }
        try {
            process(Map.of("taskId", String.valueOf(next.get(0).getId())));
        } catch (Exception e) {
            log.warn("kick drama shot queue {} failed: {}", next.get(0).getId(), e.getMessage());
        }
    }

    private void rememberProviderJob(GenerateProvider provider, GenTask task, GenerateRequest request, AiModel model) {
        if (!StringUtils.hasText(task.getProviderJobId())) {
            return;
        }
        if (provider instanceof RunningHubGenerateProvider runningHub) {
            String openApiBase = RunningHubUrls.openApiBase(request.getEndpoint(), generateProperties.getRunninghub().getBaseUrl());
            runningHub.remember(task.getProviderJobId(), request, openApiBase, resolveApiKey(model, request.getAdapterKey()));
            return;
        }
        if (provider instanceof ComfyUiGenerateProvider comfyUi) {
            comfyUi.remember(task.getProviderJobId(), request, model == null ? request.getEndpoint() : model.getEndpoint());
            return;
        }
        if (provider instanceof AgnesGenerateProvider agnes) {
            String v1Base = AgnesUrls.v1Base(request.getEndpoint(), generateProperties.getAgnes().getBaseUrl());
            agnes.remember(task.getProviderJobId(), request, v1Base, resolveApiKey(model, request.getAdapterKey()));
            return;
        }
        if (provider instanceof ArkGenerateProvider ark) {
            String v3Base = ArkUrls.v3Base(
                    request.getEndpoint(), generateProperties.getArk().getBaseUrl(),
                    model == null ? null : model.getProvider());
            ark.remember(task.getProviderJobId(), request, v3Base, resolveApiKey(model, request.getAdapterKey()));
            return;
        }
        if (provider instanceof MiniMaxGenerateProvider miniMax) {
            String origin = MiniMaxUrls.origin(request.getEndpoint(), generateProperties.getMinimax().getBaseUrl());
            miniMax.remember(task.getProviderJobId(), request, origin, resolveApiKey(model, request.getAdapterKey()));
            return;
        }
        if (provider instanceof MiniMaxSpeechProvider speech) {
            speech.remember(task.getProviderJobId(), request);
            return;
        }
        if (provider instanceof OpenAiSpeechProvider speech) {
            speech.remember(task.getProviderJobId(), request);
            return;
        }
        if (provider instanceof OpenAiImageGenerateProvider openAiImage) {
            openAiImage.remember(task.getProviderJobId(), request);
            return;
        }
        if (provider instanceof SeedreamGenerateProvider seedream) {
            seedream.remember(task.getProviderJobId(), request);
        }
    }

    private boolean hasCreations(Long taskId) {
        return creationMapper.selectCount(new LambdaQueryWrapper<Creation>().eq(Creation::getTaskId, taskId)) > 0;
    }

    private static Long taskIdOf(Map<Object, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        Object raw = payload.get("taskId");
        if (raw == null) {
            for (Map.Entry<Object, Object> entry : payload.entrySet()) {
                if (entry.getKey() != null && "taskId".equalsIgnoreCase(String.valueOf(entry.getKey()))) {
                    raw = entry.getValue();
                    break;
                }
            }
        }
        if (raw == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Object lockFor(Long taskId) {
        return taskLocks.computeIfAbsent(taskId, id -> new Object());
    }

    private boolean isOfficialSeedanceTask(GenTask task) {
        AiModel model = task.getModelId() == null ? null : aiModelMapper.selectById(task.getModelId());
        String adapter = null;
        if (task.getParams() != null && task.getParams().get("adapterKey") != null) {
            adapter = String.valueOf(task.getParams().get("adapterKey"));
        }
        adapter = AdapterCatalog.resolveKeyForGenerate(
                firstNonBlank(adapter, model == null ? null : model.getAdapterKey()),
                model == null ? null : model.getProvider(),
                model == null ? null : model.getCategory(),
                model == null ? null : model.getModelKey());
        return AdapterCatalog.isArk(adapter);
    }

    private boolean shouldIgnoreForeignFailure(GenTask task, String message) {
        String adapter = null;
        if (task.getParams() != null && task.getParams().get("adapterKey") != null) {
            adapter = String.valueOf(task.getParams().get("adapterKey"));
        }
        if (!AdapterCatalog.isArk(adapter) && task.getModelId() != null) {
            AiModel model = aiModelMapper.selectById(task.getModelId());
            if (model != null) {
                adapter = AdapterCatalog.resolveKeyForGenerate(
                        firstNonBlank(adapter, model.getAdapterKey()),
                        model.getProvider(),
                        model.getCategory(),
                        model.getModelKey());
            }
        }
        return isForeignProviderError(adapter, message);
    }

    private void storeOfficialError(GenTask task, String message) {
        if (!StringUtils.hasText(message) || GenerateTaskViews.isForeignAgnesError(message)) {
            return;
        }
        Map<String, Object> params = task.getParams() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(task.getParams());
        params.put(GenerateTaskViews.OFFICIAL_ERROR_KEY, message);
        task.setParams(params);
    }

    static boolean isForeignProviderError(String adapterKey, String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        if (AdapterCatalog.isArk(adapterKey)) {
            return message.contains("Agnes ") || message.startsWith("Agnes");
        }
        if (AdapterCatalog.isAgnes(adapterKey)) {
            return message.contains("Seedance ") || message.startsWith("Seedance");
        }
        return false;
    }

    static boolean isRecoverableIngestError(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return message.contains("下载 RunningHub")
                || message.contains("下载 Agnes")
                || message.contains("下载 Seedance")
                || message.contains("下载 MiniMax")
                || message.contains("下载 ComfyUI")
                || message.contains("ComfyUI 成片")
                || message.contains("成片回收")
                || message.contains("成片为空")
                || message.contains("成片 URL");
    }

    static boolean dramaShotSlotsFull(long running) {
        return running >= DramaContract.MAX_SHOT_GENERATE;
    }

    static boolean isRetryableSubmitError(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("http 503")
                || lower.contains("http 429")
                || lower.contains("queue is full")
                || lower.contains("rate limit")
                || message.contains("请稍后")
                || lower.contains("retry later");
    }

    private void deferSubmit(GenTask task, String message) {
        GenTask latest = genTaskMapper.selectById(task.getId());
        if (latest == null
                || TaskStatus.SUCCEEDED.equals(latest.getStatus())
                || TaskStatus.CANCELLED.equals(latest.getStatus())) {
            return;
        }
        if (latest.getCreatedAt() != null
                && latest.getCreatedAt().isBefore(OffsetDateTime.now().minusMinutes(30))) {
            finishFailed(latest, friendlyBusyMessage(message));
            return;
        }
        int retries = nextSubmitRetry(latest);
        long backoffMs = quotaBackoffMs(retries);
        long retryAt = System.currentTimeMillis() + backoffMs;
        if (isArkTask(latest) || isQuotaMessage(message)) {
            arkBusyUntilMs = Math.max(arkBusyUntilMs, retryAt);
        }
        Map<String, Object> params = latest.getParams() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(latest.getParams());
        params.put("submitRetries", retries);
        params.put("retryAfterEpochMs", retryAt);
        latest.setParams(params);
        latest.setStatus(TaskStatus.PENDING);
        latest.setProviderJobId(null);
        latest.setFinishedAt(null);
        latest.setErrorMessage(friendlyBusyMessage(message));
        latest.setProgress(Math.max(5, latest.getProgress() == null ? 5 : latest.getProgress()));
        genTaskMapper.updateById(latest);
        log.info("generate task {} deferred {}ms retries={}", latest.getId(), backoffMs, retries);
    }

    static String friendlyBusyMessage(String message) {
        if (isQuotaMessage(message)) {
            return GenerateTaskViews.QUOTA_MESSAGE;
        }
        if (message != null && message.toLowerCase().contains("queue is full")) {
            return "Agnes 视频队列繁忙，请稍后重试";
        }
        return message == null || message.isBlank() ? "生成失败" : message;
    }

    static boolean isQuotaMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("exceeded the quota")
                || lower.contains("http 429")
                || lower.contains("rate limit");
    }

    static long quotaBackoffMs(int retries) {
        int n = Math.max(1, retries);
        long seconds = Math.min(120L, 20L * (1L << Math.min(n - 1, 3)));
        return seconds * 1000L;
    }

    static boolean shouldDelayRetry(GenTask task) {
        if (task == null || task.getParams() == null || task.getParams().get("retryAfterEpochMs") == null) {
            return false;
        }
        try {
            long retryAt = Long.parseLong(String.valueOf(task.getParams().get("retryAfterEpochMs")).trim());
            return retryAt > System.currentTimeMillis();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static int nextSubmitRetry(GenTask task) {
        if (task.getParams() == null || task.getParams().get("submitRetries") == null) {
            return 1;
        }
        try {
            return Integer.parseInt(String.valueOf(task.getParams().get("submitRetries")).trim()) + 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private boolean isArkTask(GenTask task) {
        if (task == null) {
            return false;
        }
        String adapter = null;
        if (task.getParams() != null && task.getParams().get("adapterKey") != null) {
            adapter = String.valueOf(task.getParams().get("adapterKey"));
        }
        return AdapterCatalog.isArk(adapter) || GenerateTaskViews.isOfficialSeedance(task);
    }

    private void claimIdlePending(String stream, String group, String consumerName) {
        try {
            PendingMessages pending = stringRedisTemplate.opsForStream()
                    .pending(stream, group, Range.unbounded(), 20L);
            if (pending == null || pending.isEmpty()) {
                return;
            }
            List<RecordId> idle = new ArrayList<>();
            for (PendingMessage message : pending) {
                Duration elapsed = message.getElapsedTimeSinceLastDelivery();
                if (elapsed != null && elapsed.compareTo(Duration.ofSeconds(15)) >= 0) {
                    idle.add(message.getId());
                }
            }
            if (idle.isEmpty()) {
                return;
            }
            stringRedisTemplate.opsForStream().claim(
                    stream, group, consumerName, Duration.ofSeconds(15), idle.toArray(RecordId[]::new));
            log.info("claimed {} idle generate stream messages", idle.size());
        } catch (Exception e) {
            log.debug("claim idle generate stream: {}", e.getMessage());
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

    private GenerateProvider bindAndResolve(GenTask task, AiModel model, GenerateRequest request) {
        String modelKey = model == null ? request.getModelKey() : model.getModelKey();
        String requestedAdapter = firstNonBlank(request.getAdapterKey(), model == null ? null : model.getAdapterKey());
        String adapterKey = AdapterCatalog.resolveKeyForGenerate(
                requestedAdapter,
                model == null ? null : model.getProvider(),
                model == null ? null : model.getCategory(),
                modelKey);
        if (requestedAdapter != null && !requestedAdapter.isBlank()
                && adapterKey != null && !adapterKey.equalsIgnoreCase(requestedAdapter.trim())) {
            log.warn("generate task {} official seedance forced adapter {} -> {} modelKey={}",
                    task.getId(), requestedAdapter, adapterKey, modelKey);
        }
        request.setAdapterKey(adapterKey);
        if (model != null) {
            if (AdapterCatalog.isArk(adapterKey) || AdapterCatalog.isSeedream(adapterKey)) {
                request.setEndpoint(ArkUrls.v3Base(
                        model.getEndpoint(), generateProperties.getArk().getBaseUrl(), model.getProvider()));
            } else {
                request.setEndpoint(model.getEndpoint());
            }
            request.setAdapterConfig(model.getAdapterConfig());
            request.setApiKey(resolveApiKey(model, adapterKey));
        }
        GenerateProvider provider = providerRegistry.resolveByAdapter(
                adapterKey,
                model == null ? null : model.getProvider(),
                model == null ? null : model.getCategory(),
                modelKey);
        log.info("generate task {} routed adapter={} transport={} modelId={} modelKey={} provider={}",
                task.getId(),
                adapterKey,
                provider.providerKey(),
                model == null ? null : model.getId(),
                request.getModelKey(),
                model == null ? null : model.getProvider());
        return provider;
    }

    private String resolveApiKey(AiModel model, String adapterKey) {
        if (model != null && StringUtils.hasText(model.getApiKeyEnc())) {
            String decrypted = encryptor.decrypt(model.getApiKeyEnc());
            if (StringUtils.hasText(decrypted)) {
                String normalized = ArkKeys.normalize(decrypted);
                if (StringUtils.hasText(normalized)) {
                    return normalized;
                }
            }
        }
        String resolved = adapterKey;
        if (resolved == null && model != null) {
            resolved = AdapterCatalog.resolveKey(
                    model.getAdapterKey(), model.getProvider(), model.getCategory(), model.getModelKey());
        }
        if (AdapterCatalog.isAgnes(resolved)) {
            return generateProperties.getAgnes().getApiKey();
        }
        if (AdapterCatalog.isArk(resolved) || AdapterCatalog.isSeedream(resolved)) {
            return generateProperties.getArk().getApiKey();
        }
        if (AdapterCatalog.isMiniMaxOfficial(resolved, model == null ? null : model.getProvider())) {
            return generateProperties.getMinimax().getApiKey();
        }
        if (AdapterCatalog.isMiniMaxSpeech(resolved)) {
            return generateProperties.getMinimax().getApiKey();
        }
        if (AdapterCatalog.isOpenAiSpeech(resolved)) {
            return generateProperties.getSpeech().getApiKey();
        }
        if (AdapterCatalog.isOpenAiImage(resolved)) {
            return generateProperties.getOpenAiImage().getApiKey();
        }
        if (AdapterCatalog.isRunningHub(resolved)
                || (model != null && model.getProvider() != null
                && RunningHubGenerateProvider.KEY.equalsIgnoreCase(model.getProvider()))) {
            return generateProperties.getRunninghub().getApiKey();
        }
        return null;
    }

    private static String firstNonBlank(String first, String second) {
        if (StringUtils.hasText(first)) {
            return first.trim();
        }
        if (StringUtils.hasText(second)) {
            return second.trim();
        }
        return null;
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
