package cn.aigc.dit.server.service;

import cn.aigc.dit.generate.GenerateRequest;
import cn.aigc.dit.server.entity.AiModel;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GenerateServiceBillingQuantityTest {

    @Test
    void audioUsesPromptCharacterCountWithoutDuration() {
        GenerateRequest request = new GenerateRequest()
                .setCategory("audio")
                .setPrompt("你好，世界")
                .setDurationSec(null);
        AiModel model = new AiModel();
        model.setAdapterKey("minimax-speech");
        model.setProvider("minimax");
        model.setModelKey("speech-2.8-hd");

        // MiniMax 口径：4 汉字 ×2 + 全角逗号 ×1 = 9
        assertEquals(9, GenerateService.billingQuantity(model, request));
    }

    @Test
    void audioFollowsModelConfiguredCharacterWeight() {
        GenerateRequest request = new GenerateRequest()
                .setCategory("audio")
                .setPrompt("你好，世界")
                .setDurationSec(null);
        AiModel model = new AiModel();
        model.setAdapterKey("ark-speech");
        model.setProvider("火山引擎");
        model.setModelKey("doubao-seed-tts");
        model.setPricing(Map.of("cjkWeight", 1));

        // 火山口径：1 字 = 1 字符 → 4 汉字 + 全角逗号 = 5
        assertEquals(5, GenerateService.billingQuantity(model, request));
    }

    @Test
    void imageAllowsNullDurationWithoutUnboxing() {
        GenerateRequest request = new GenerateRequest()
                .setCategory("image")
                .setPrompt("一张图片")
                .setDurationSec(null);

        assertNull(GenerateService.billingQuantity(null, request));
    }
}
