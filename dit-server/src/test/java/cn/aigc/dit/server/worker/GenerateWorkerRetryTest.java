package cn.aigc.dit.server.worker;

import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.server.drama.DramaContract;
import cn.aigc.dit.server.generate.GenerateTaskViews;
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
        assertTrue(GenerateWorker.isRetryableSubmitError(
                "Seedance HTTP 429: too many requests, rate limit"));
        assertFalse(GenerateWorker.isRetryableSubmitError("Agnes HTTP 400: size must be 720P"));
    }

    @Test
    void accountQuotaAndBalanceFailWithoutRetry() {
        assertFalse(GenerateWorker.isRetryableSubmitError(
                "Seedance HTTP 429: The request has exceeded the quota. Request id: 0217"));
        assertFalse(GenerateWorker.isRetryableSubmitError(
                "Seedance HTTP 429: You have exceeded the monthly usage quota. "
                        + "It will reset at 2026-11-21 23:59:59 +0800 CST."));
        assertFalse(GenerateWorker.isRetryableSubmitError(
                "RunningHub 返回错误：Your balance is insufficient. Please top up your account. 请稍后"));
        assertFalse(GenerateWorker.isRetryableSubmitError(
                "Seedance HTTP 400: Your account does not have a valid AgentPlan subscription, "
                        + "or your subscription has expired. 请稍后重试"));
        assertFalse(GenerateWorker.isRetryableSubmitError("额度已用完，请稍后重试"));
    }

    @Test
    void mapsQueueFullToFriendlyMessage() {
        assertEquals("Agnes 视频队列繁忙，请稍后重试",
                GenerateWorker.friendlyBusyMessage("Agnes HTTP 503: video queue is full, please retry later"));
        assertEquals(GenerateTaskViews.QUOTA_EXHAUSTED_MESSAGE,
                GenerateWorker.friendlyBusyMessage(
                        "Seedance HTTP 429: The request has exceeded the quota."));
        assertEquals(GenerateTaskViews.QUOTA_EXHAUSTED_MESSAGE,
                GenerateWorker.friendlyBusyMessage(
                        "Seedance HTTP 429: You have exceeded the monthly usage quota. "
                                + "It will reset at 2026-11-21 23:59:59 +0800 CST."));
    }

    @Test
    void seedanceQuotaBackoffGrowsThenCaps() {
        assertEquals(20_000L, GenerateWorker.quotaBackoffMs(1));
        assertEquals(40_000L, GenerateWorker.quotaBackoffMs(2));
        assertEquals(80_000L, GenerateWorker.quotaBackoffMs(3));
        assertEquals(120_000L, GenerateWorker.quotaBackoffMs(4));
        assertEquals(120_000L, GenerateWorker.quotaBackoffMs(9));
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
    void delaysRetryUntilRetryAfterEpoch() {
        cn.aigc.dit.server.entity.GenTask task = new cn.aigc.dit.server.entity.GenTask();
        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("retryAfterEpochMs", System.currentTimeMillis() + 60_000L);
        task.setParams(params);
        assertTrue(GenerateWorker.shouldDelayRetry(task));
        params.put("retryAfterEpochMs", System.currentTimeMillis() - 1_000L);
        assertFalse(GenerateWorker.shouldDelayRetry(task));
    }

    @Test
    void dramaShotQueueStartsAfterEightRunning() {
        assertFalse(GenerateWorker.dramaShotSlotsFull(DramaContract.MAX_SHOT_GENERATE - 1));
        assertTrue(GenerateWorker.dramaShotSlotsFull(DramaContract.MAX_SHOT_GENERATE));
        assertTrue(GenerateWorker.dramaShotSlotsFull(DramaContract.MAX_SHOT_GENERATE + 3));
    }
}
