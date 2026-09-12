package cn.aigc.dit.generate.ark;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArkErrorsTest {

    @Test
    void mapsRealPersonBodyToChinese() {
        String message = ArkErrors.message("""
                {"error":{"code":"InputImageSensitiveContentDetected",\
                "message":"The request failed because the input image 'content[1]' may contain real person."}}
                """);
        assertEquals(ArkErrors.REAL_PERSON_MESSAGE, message);
    }

    @Test
    void keepsOrdinaryArkMessage() {
        String message = ArkErrors.message("{\"error\":{\"message\":\"invalid model\"}}");
        assertEquals("invalid model", message);
        assertTrue(JdkArkHttp.arkError(400, "{\"error\":{\"message\":\"invalid model\"}}", true)
                .contains("invalid model"));
    }
}
