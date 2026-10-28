package cn.aigc.dit.server.worker;

import cn.aigc.dit.server.drama.DramaShotCaptionPostProcessService;
import cn.aigc.dit.server.drama.entity.DramaShot;
import cn.aigc.dit.server.drama.entity.DramaShotCaption;
import cn.aigc.dit.server.drama.mapper.DramaShotMapper;
import cn.aigc.dit.server.drama.mapper.DramaShotCaptionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Durable async worker for local VAD/ASS post-processing. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "dit.generate.vad", name = "enabled", havingValue = "true")
public class DramaShotCaptionWorker {

    private static final int CONCURRENCY = 2;

    private final DramaShotCaptionMapper captionMapper;
    private final DramaShotCaptionPostProcessService captionService;
    private final DramaShotMapper shotMapper;
    private final AtomicBoolean backfillStarted = new AtomicBoolean(false);
    private final AtomicInteger inFlight = new AtomicInteger(0);
    private final ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY, r -> {
        Thread t = new Thread(r, "drama-caption-worker");
        t.setDaemon(true);
        return t;
    });

    @Scheduled(fixedDelay = 2000, initialDelay = 5000)
    public void tick() {
        if (backfillStarted.compareAndSet(false, true)) {
            backfillExistingShots();
        }
        while (inFlight.get() < CONCURRENCY) {
            DramaShotCaption task = claimNext();
            if (task == null) return;
            inFlight.incrementAndGet();
            executor.submit(() -> {
                try {
                    captionService.process(task);
                } catch (RuntimeException e) {
                    log.warn("caption worker failed task={}", task.getId(), e);
                    task.setStatus("FAILED");
                    task.setErrorMessage(e.getMessage() == null ? "字幕任务失败" : e.getMessage());
                    captionMapper.updateById(task);
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        }
    }

    private DramaShotCaption claimNext() {
        List<DramaShotCaption> candidates = captionMapper.selectList(new LambdaQueryWrapper<DramaShotCaption>()
                .eq(DramaShotCaption::getStatus, "PENDING")
                .orderByAsc(DramaShotCaption::getId)
                .last("limit 8"));
        OffsetDateTime now = OffsetDateTime.now();
        for (DramaShotCaption task : candidates) {
            if (!readyForAttempt(task, now)) continue;
            int claimed = captionMapper.update(null, new LambdaUpdateWrapper<DramaShotCaption>()
                    .eq(DramaShotCaption::getId, task.getId())
                    .eq(DramaShotCaption::getStatus, "PENDING")
                    .set(DramaShotCaption::getStatus, "RUNNING"));
            if (claimed == 1) {
                task.setStatus("RUNNING");
                return task;
            }
        }
        return null;
    }

    /** Exponential backoff: 2s, 4s, 8s after failed attempts before re-claim. */
    static boolean readyForAttempt(DramaShotCaption task, OffsetDateTime now) {
        int attempt = task.getAttemptCount() == null ? 0 : task.getAttemptCount();
        if (attempt <= 0 || task.getUpdatedAt() == null) return true;
        long backoffSec = 1L << Math.min(attempt, 5);
        return !task.getUpdatedAt().plusSeconds(backoffSec).isAfter(now);
    }

    /** Enqueue historical generated shots once after VAD is enabled / processor upgrade. */
    private void backfillExistingShots() {
        List<DramaShot> shots = shotMapper.selectList(new LambdaQueryWrapper<DramaShot>()
                .isNotNull(DramaShot::getVideoKey)
                .isNotNull(DramaShot::getDialogue)
                .last("LIMIT 200"));
        for (DramaShot shot : shots) {
            if (captionService.findForSource(shot.getId(), shot.getVideoKey()) == null) {
                captionService.enqueue(shot.getId(), shot.getGenTaskId(), shot.getUserId(), shot.getVideoKey());
            }
        }
        log.info("caption backfill scanned {} historical shots for {}", shots.size(),
                DramaShotCaptionPostProcessService.PROCESSOR_VERSION);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
