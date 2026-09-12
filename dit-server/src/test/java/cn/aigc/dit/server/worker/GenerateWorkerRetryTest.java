package cn.aigc.dit.server.worker;

import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.server.drama.DramaContract;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerateWorkerRetryTest {

    @Test
    void treatsAgnesQueueFullAsRetryableSubmit() {
        assertTrue(GenerateWorker.isRetryableSubmitError(
                "Agnes HTTP 503: video queue is full, please retry later"));
        assertTrue(GenerateWorker.isRetryableSubmitError(
                "Agnes HTTP 429: video status query rate limit exceeded"));
        assertFalse(GenerateWorker.isRetryableSubmitError("Agnes HTTP 400: size must be 720P"));
    }

    @Test
    void mapsQueueFullToFriendlyMessage() {
        assertEquals("Agnes 视频队列繁忙，请稍后重试",
                GenerateWorker.friendlyBusyMessage("Agnes HTTP 503: video queue is full, please retry later"));
    }

    @Test
    void ignoresAgnesPollErrorOnOfficialSeedance() {
        assertTrue(GenerateWorker.isForeignProviderError(
                AdapterCatalog.SEEDANCE_2_0, "Agnes 查询 HTTP 404"));
        assertTrue(GenerateWorker.isForeignProviderError(
                AdapterCatalog.SEEDANCE_2_0, "Agnes 任务不存在"));
        assertFalse(GenerateWorker.isForeignProviderError(
                AdapterCatalog.SEEDANCE_2_0, "Seedance 查询 HTTP 404"));
        assertFalse(GenerateWorker.isForeignProviderError(
                AdapterCatalog.AGNES_VIDEO_2_5, "Agnes 查询 HTTP 404"));
    }

    @Test
    void dramaShotQueueStartsAfterEightRunning() {
        assertFalse(GenerateWorker.dramaShotSlotsFull(DramaContract.MAX_SHOT_GENERATE - 1));
        assertTrue(GenerateWorker.dramaShotSlotsFull(DramaContract.MAX_SHOT_GENERATE));
        assertTrue(GenerateWorker.dramaShotSlotsFull(DramaContract.MAX_SHOT_GENERATE + 3));
    }
}
