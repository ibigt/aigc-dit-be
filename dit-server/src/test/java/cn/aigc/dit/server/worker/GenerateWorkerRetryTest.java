package cn.aigc.dit.server.worker;

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
}
