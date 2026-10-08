package cn.aigc.dit.server.generate;

import cn.aigc.dit.common.constant.TaskStatus;
import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.server.entity.GenTask;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerateTaskViewsTest {

    @Test
    void hidesForeignAgnesKillWhileArkJobStillExists() {
        GenTask task = seedanceTask();
        task.setStatus(TaskStatus.FAILED);
        task.setProviderJobId("cgt-20260912194437-n27zz");
        task.setErrorMessage("Agnes 查询 HTTP 404");

        assertTrue(GenerateTaskViews.shouldKeepPolling(task));
        assertEquals(TaskStatus.RUNNING, GenerateTaskViews.publicStatus(task));
        assertNull(GenerateTaskViews.publicError(task));
    }

    @Test
    void prefersPersistedOfficialErrorWhenSubmitWasOverwritten() {
        GenTask task = seedanceTask();
        task.setStatus(TaskStatus.FAILED);
        task.setErrorMessage("Agnes 查询 HTTP 404");
        task.getParams().put(GenerateTaskViews.OFFICIAL_ERROR_KEY,
                "Seedance HTTP 400: content may contain real person");

        assertFalse(GenerateTaskViews.shouldKeepPolling(task));
        assertEquals(TaskStatus.FAILED, GenerateTaskViews.publicStatus(task));
        assertEquals("Seedance HTTP 400: content may contain real person", GenerateTaskViews.publicError(task));
    }

    @Test
    void keepsOfficialRealPersonWording() {
        assertEquals("Seedance HTTP 400: The request failed because the input image may contain real person.",
                GenerateTaskViews.friendlySeedanceError(
                        "Seedance HTTP 400: The request failed because the input image may contain real person."));
    }

    @Test
    void hidesForeignAgnesErrorOnOfficialSeedance() {
        GenTask failed = seedanceTask();
        failed.setStatus(TaskStatus.FAILED);
        failed.setErrorMessage("Agnes HTTP 404");
        assertNull(GenerateTaskViews.publicError(failed));

        GenTask running = seedanceTask();
        running.setStatus(TaskStatus.RUNNING);
        running.setProviderJobId("cgt-seedance");
        running.setErrorMessage("Agnes 查询 HTTP 404");
        assertEquals(TaskStatus.RUNNING, GenerateTaskViews.publicStatus(running));
        assertNull(GenerateTaskViews.publicError(running));
    }

    @Test
    void mapsQuotaExceededToChinese() {
        assertEquals(GenerateTaskViews.QUOTA_EXHAUSTED_MESSAGE,
                GenerateTaskViews.friendlySeedanceError(
                        "Seedance HTTP 429: The request has exceeded the quota."));
        assertEquals(GenerateTaskViews.QUOTA_MESSAGE,
                GenerateTaskViews.friendlySeedanceError(
                        "Seedance HTTP 429: too many requests, rate limit"));
    }

    private static GenTask seedanceTask() {
        GenTask task = new GenTask();
        task.setId(100L);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("adapterKey", AdapterCatalog.SEEDANCE_2_0);
        params.put("modelKey", "doubao-seedance-2.0-fast");
        task.setParams(params);
        return task;
    }
}
