package cn.aigc.dit.server.service;

import cn.aigc.dit.generate.GenerateRequest;
import cn.aigc.dit.server.entity.AiModel;
import org.junit.jupiter.api.Test;

import java.util.List;
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

    @Test
    void videoBillsReferenceVideoSecondsAtTheHighestTier() {
        // 分档价：480P=7、720P=15、1080P=33 积分/秒（最高档 33）
        Map<String, Object> pricing = Map.of(
                "unit", "秒",
                "sale", Map.of("output", "33 积分", "tiers", Map.of("480P", "7", "720P", "15", "1080P", "33")));
        GenerateRequest request = new GenerateRequest()
                .setCategory("video")
                .setDurationSec(5)
                .setReferences(List.of(
                        new cn.aigc.dit.generate.ReferenceAsset("video", "users/1/assets/clip.mp4", 6000L),
                        new cn.aigc.dit.generate.ReferenceAsset("image", "users/1/assets/ref.png", null)));

        // 5 秒成片按 720P 档 15 计 = 75；6 秒参考视频按最高档 33 计 = 198
        long cost = cn.aigc.dit.server.credit.CreditCalculator.calculate(
                pricing, "video", "720P", null, 5, 1, GenerateService.referenceVideoSeconds(request));
        assertEquals(75 + 198, cost);
    }

    @Test
    void videoIgnoresReferenceSecondsWithoutAnActualVideoReference() {
        // 只有图片参考（短剧分镜链路）：不收参考秒数
        GenerateRequest request = new GenerateRequest()
                .setCategory("video")
                .setDurationSec(5)
                .setReferences(List.of(
                        new cn.aigc.dit.generate.ReferenceAsset("image", "users/1/assets/ref.png", null)));

        assertEquals(0, GenerateService.referenceVideoSeconds(request));
    }

    @Test
    void referenceVideoSecondsRoundUpFromMeasuredDuration() {
        GenerateRequest request = new GenerateRequest()
                .setCategory("video")
                .setDurationSec(5)
                .setReferences(List.of(
                        new cn.aigc.dit.generate.ReferenceAsset("video", "users/1/a.mp4", 6500L),
                        new cn.aigc.dit.generate.ReferenceAsset("video", "users/1/b.mp4", 2500L)));

        // 6.5s + 2.5s = 9s
        assertEquals(9, GenerateService.referenceVideoSeconds(request));
    }

    @Test
    void clientReportedSecondsOnlyRaiseTheBillNeverLowerIt() {
        // 服务端探测不到时长（缺 ffprobe）时用前端上报值兜底
        GenerateRequest fallback = new GenerateRequest()
                .setCategory("video")
                .setDurationSec(5)
                .setReferenceVideoSeconds(6)
                .setReferences(List.of(
                        new cn.aigc.dit.generate.ReferenceAsset("video", "users/1/a.mp4", null)));
        assertEquals(6, GenerateService.referenceVideoSeconds(fallback));

        // 前端少报：服务端实测为准
        GenerateRequest underreport = new GenerateRequest()
                .setCategory("video")
                .setDurationSec(5)
                .setReferenceVideoSeconds(1)
                .setReferences(List.of(
                        new cn.aigc.dit.generate.ReferenceAsset("video", "users/1/a.mp4", 8000L)));
        assertEquals(8, GenerateService.referenceVideoSeconds(underreport));

        // 没有视频参考时一律不收
        GenerateRequest noVideo = new GenerateRequest()
                .setCategory("video")
                .setDurationSec(5)
                .setReferenceVideoSeconds(6)
                .setReferences(List.of(
                        new cn.aigc.dit.generate.ReferenceAsset("image", "users/1/a.png", null)));
        assertEquals(0, GenerateService.referenceVideoSeconds(noVideo));
    }

    @Test
    void videoWithoutDurationIsBilledAsOneSecond() {
        GenerateRequest request = new GenerateRequest()
                .setCategory("video")
                .setDurationSec(null);

        assertNull(GenerateService.billingQuantity(null, request));
        // 缺省按 1 秒计（submit 里视频已兜底为 5 秒，这里覆盖直接调用计价器的情形）
        Map<String, Object> pricing = Map.of("unit", "秒", "sale", Map.of("output", "12 积分"));
        assertEquals(12L, cn.aigc.dit.server.credit.CreditCalculator.calculate(pricing, "video", null, null, null, 1, 0));
    }
}
