package cn.aigc.dit.server.worker;

import cn.aigc.dit.server.drama.DramaShotCaptionPostProcessService;
import cn.aigc.dit.server.drama.entity.DramaShot;
import cn.aigc.dit.server.drama.entity.DramaShotCaption;
import cn.aigc.dit.server.drama.mapper.DramaShotMapper;
import cn.aigc.dit.server.drama.mapper.DramaShotCaptionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Durable async worker for local VAD/ASS post-processing. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "dit.generate.vad", name = "enabled", havingValue = "true")
public class DramaShotCaptionWorker {

    private final DramaShotCaptionMapper captionMapper;
    private final DramaShotCaptionPostProcessService captionService;
    private final DramaShotMapper shotMapper;
    private final AtomicBoolean backfillStarted = new AtomicBoolean(false);

    @Scheduled(fixedDelay = 2000, initialDelay = 5000)
    public void tick() {
        if (backfillStarted.compareAndSet(false, true)) {
            backfillExistingShots();
        }
        DramaShotCaption task = captionMapper.selectOne(new LambdaQueryWrapper<DramaShotCaption>()
                .eq(DramaShotCaption::getStatus, "PENDING")
                .orderByAsc(DramaShotCaption::getId)
                .last("limit 1"));
        if (task == null) return;
        int claimed = captionMapper.update(null, new LambdaUpdateWrapper<DramaShotCaption>()
                .eq(DramaShotCaption::getId, task.getId())
                .eq(DramaShotCaption::getStatus, "PENDING")
                .set(DramaShotCaption::getStatus, "RUNNING"));
        if (claimed != 1) return;
        try {
            task.setStatus("RUNNING");
            captionService.process(task);
        } catch (RuntimeException e) {
            log.warn("caption worker failed task={}", task.getId(), e);
            task.setStatus("FAILED");
            task.setErrorMessage(e.getMessage() == null ? "字幕任务失败" : e.getMessage());
            captionMapper.updateById(task);
        }
    }

    /** Enqueue historical generated shots once after VAD is enabled. */
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
        log.info("caption backfill scanned {} historical shots", shots.size());
    }
}
