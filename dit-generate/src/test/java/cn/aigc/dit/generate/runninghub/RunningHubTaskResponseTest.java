package cn.aigc.dit.generate.runninghub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * RunningHub 接口字段命名曾在多版本里混用 camelCase / snake_case / 简写，
 * 解析时只识别 {@code taskId} 会偶发 "未返回 taskId"。这里覆盖三种命名以及
 * 嵌套在 {@code data} 子对象里的两种形态，避免回归。
 */
class RunningHubTaskResponseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 复刻 RunningHubGenerateProvider#parseTaskResponse 的实际解析路径：
     * 顶层有 {@code data} 子对象时，整体解析该子对象。{@code code != 0/200}
     * 的失败路径不在此覆盖。
     */
    private RunningHubTaskResponse parse(String raw) throws Exception {
        JsonNode root = mapper.readTree(raw);
        JsonNode payload = root.has("data") && root.get("data").isObject() ? root.get("data") : root;
        return mapper.treeToValue(payload, RunningHubTaskResponse.class);
    }

    @Test
    void readsCamelCaseTaskId() throws Exception {
        assertEquals("rh-1", parse("{\"taskId\":\"rh-1\",\"status\":\"RUNNING\"}").getTaskId());
    }

    @Test
    void readsSnakeCaseTaskId() throws Exception {
        assertEquals("rh-2", parse("{\"task_id\":\"rh-2\",\"status\":\"RUNNING\"}").getTaskId());
    }

    @Test
    void readsUppercaseTaskIdAlias() throws Exception {
        assertEquals("rh-3", parse("{\"taskID\":\"rh-3\",\"status\":\"RUNNING\"}").getTaskId());
    }

    @Test
    void readsNestedTaskIdUnderData() throws Exception {
        assertEquals("rh-4",
                parse("{\"code\":0,\"data\":{\"task_id\":\"rh-4\",\"status\":\"RUNNING\"}}").getTaskId());
    }

    @Test
    void readsCamelCaseTaskIdUnderData() throws Exception {
        assertEquals("rh-5", parse("{\"data\":{\"taskId\":\"rh-5\"}}").getTaskId());
    }

    @Test
    void readsErrorMessageAlias() throws Exception {
        // 只给原字段
        RunningHubTaskResponse a = parse(
                "{\"taskId\":\"rh-x\",\"status\":\"FAILED\",\"errorMessage\":\"oops\"}");
        assertEquals("oops", a.getErrorMessage());

        // 只给别名字段（接口命名迁移期）
        RunningHubTaskResponse b = parse(
                "{\"taskId\":\"rh-x\",\"status\":\"FAILED\",\"error_msg\":\"nope\"}");
        assertEquals("nope", b.getErrorMessage());
    }
}