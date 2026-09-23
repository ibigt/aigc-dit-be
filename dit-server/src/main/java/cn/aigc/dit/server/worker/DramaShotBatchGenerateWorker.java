package cn.aigc.dit.server.worker;

import cn.aigc.dit.server.drama.DramaShotBatchGenerateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 分镜批量出片推进：扫描 RUNNING 批次并 advance。关浏览器后仍继续。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "dit.drama.batch-generate.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DramaShotBatchGenerateWorker {

    private final DramaShotBatchGenerateService batchGenerateService;

    @Scheduled(fixedDelay = 2000)
    public void tick() {
        try {
            batchGenerateService.advanceRunningJobs();
        } catch (RuntimeException e) {
            log.warn("drama batch generate worker tick failed: {}", e.getMessage());
        }
    }
}
