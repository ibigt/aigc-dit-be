package cn.aigc.dit.generate.ark;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArkUrlsTest {

    @Test
    void appendsApiV3WhenMissing() {
        assertEquals("https://ark.cn-beijing.volces.com/api/v3",
                ArkUrls.v3Base("https://ark.cn-beijing.volces.com", "x"));
        assertEquals("https://ark.cn-beijing.volces.com/api/v3",
                ArkUrls.v3Base("https://ark.cn-beijing.volces.com/api/v3/", "x"));
        assertEquals("https://ark.cn-beijing.volces.com/api/v3",
                ArkUrls.v3Base("https://ark.cn-beijing.volces.com/api/v3/contents/generations/tasks", "x"));
    }

    @Test
    void keepsAgentPlanAndCodingBasesWithoutRewriting() {
        assertEquals("https://ark.cn-beijing.volces.com/api/plan/v3",
                ArkUrls.v3Base("https://ark.cn-beijing.volces.com/api/plan/v3", "x"));
        assertEquals("https://ark.cn-beijing.volces.com/api/plan/v3",
                ArkUrls.v3Base("https://ark.cn-beijing.volces.com/api/plan/v3/contents/generations/tasks", "x"));
        assertEquals("https://ark.cn-beijing.volces.com/api/coding/v3",
                ArkUrls.v3Base("https://ark.cn-beijing.volces.com/api/coding/v3", "x"));
        assertEquals("https://ark.cn-beijing.volces.com/api/v3",
                ArkUrls.v3Base("https://ark.cn-beijing.volces.com/api/v3", "x"));
        assertEquals("https://ark.cn-beijing.volces.com/api/plan/v3",
                ArkUrls.v3Base("https://ark.cn-beijing.volces.com/api/v3", "x", "官方API（token plan）"));
        assertEquals("https://ark.cn-beijing.volces.com/api/v3",
                ArkUrls.v3Base("https://ark.cn-beijing.volces.com/api/v3", "x", "官方API"));
    }

    @Test
    void buildsCreateAndQueryUrls() {
        String base = "https://ark.cn-beijing.volces.com/api/plan/v3";
        assertEquals("https://ark.cn-beijing.volces.com/api/plan/v3/contents/generations/tasks",
                ArkUrls.createTask(base));
        assertEquals("https://ark.cn-beijing.volces.com/api/plan/v3/contents/generations/tasks/cgt-1",
                ArkUrls.queryTask(base, "cgt-1"));
        assertEquals(List.of(
                        "https://ark.cn-beijing.volces.com/api/plan/v3/contents/generations/tasks/cgt-1",
                        "https://ark.cn-beijing.volces.com/api/v3/contents/generations/tasks/cgt-1"),
                ArkUrls.queryUrls(base, "cgt-1"));
        assertEquals("https://ark.cn-beijing.volces.com/api/v3",
                ArkUrls.baseOfQueryUrl("https://ark.cn-beijing.volces.com/api/v3/contents/generations/tasks/cgt-1"));
    }
}
