package cn.aigc.dit.generate.describe;

import cn.aigc.dit.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArkImageDescribeClientTest {

    @Test
    void responsesUrlAppendsPath() {
        assertEquals(
                "https://ark.cn-beijing.volces.com/api/v3/responses",
                ArkImageDescribeClient.responsesUrl("https://ark.cn-beijing.volces.com/api/v3"));
        assertEquals(
                "https://ark.cn-beijing.volces.com/api/plan/v3/responses",
                ArkImageDescribeClient.responsesUrl("https://ark.cn-beijing.volces.com/api/plan/v3/"));
        assertEquals(
                "https://ark.cn-beijing.volces.com/api/v3/responses",
                ArkImageDescribeClient.responsesUrl("https://ark.cn-beijing.volces.com/api/v3/responses"));
    }

    @Test
    void extractTextFromOutputArray() {
        String body = """
                {"output":[{"type":"message","content":[{"type":"output_text","text":"【identity】短发\\n【t2i_prompt】全身"}]}]}
                """;
        assertEquals("【identity】短发\n【t2i_prompt】全身", ArkImageDescribeClient.extractText(body));
    }

    @Test
    void extractTextThrowsOnApiError() {
        BizException error = assertThrows(BizException.class,
                () -> ArkImageDescribeClient.extractText("{\"error\":{\"message\":\"quota exceeded\"}}"));
        assertTrue(error.getMessage().contains("quota exceeded"));
    }

    @Test
    void normalizeMimeDefaultsAndStripsParams() {
        assertEquals("image/jpeg", ArkImageDescribeClient.normalizeMime(null));
        assertEquals("image/png", ArkImageDescribeClient.normalizeMime("image/png; charset=utf-8"));
        assertEquals("image/jpeg", ArkImageDescribeClient.normalizeMime("application/octet-stream"));
    }
}
