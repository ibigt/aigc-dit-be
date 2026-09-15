package cn.aigc.dit.server.service;

import cn.aigc.dit.generate.GenerateRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GenerateServiceBillingQuantityTest {

    @Test
    void audioUsesPromptCharacterCountWithoutDuration() {
        GenerateRequest request = new GenerateRequest()
                .setCategory("audio")
                .setPrompt("你好，世界")
                .setDurationSec(null);

        assertEquals(5, GenerateService.billingQuantity(request));
    }

    @Test
    void imageAllowsNullDurationWithoutUnboxing() {
        GenerateRequest request = new GenerateRequest()
                .setCategory("image")
                .setPrompt("一张图片")
                .setDurationSec(null);

        assertNull(GenerateService.billingQuantity(request));
    }
}
