package cn.aigc.dit.server.credit;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CreditCalculatorTest {

    @Test
    void videoPerSecondUsesDurationCopiesAndResolution() {
        Map<String, Object> pricing = Map.of(
                "unit", "秒",
                "sale", Map.of("output", "12 积分"));
        // 12 * 5s * 1.5(2K) * 2 copies = 180
        assertEquals(180L, CreditCalculator.calculate(pricing, "video", "2K", null, 5, 2));
    }

    @Test
    void video768PChargesHalfOfBase() {
        Map<String, Object> pricing = Map.of(
                "unit", "秒",
                "sale", Map.of("output", "12 积分"));
        // 12 * 5s * 0.5(768P) * 1 = 30；修复前 768P 落默认倍率 1.0 会扣 60
        assertEquals(30L, CreditCalculator.calculate(pricing, "video", "768P", null, 5, 1));
    }

    @Test
    void tieredVideoPriceOverridesResolutionMultiplier() {
        Map<String, Object> pricing = Map.of(
                "unit", "秒",
                "sale", Map.of("output", "12 积分", "tiers", Map.of("768P", "5", "2K", "20")));
        // 分档价直接生效：768P=5/s → 25，2K=20/s → 100
        assertEquals(25L, CreditCalculator.calculate(pricing, "video", "768P", null, 5, 1));
        assertEquals(100L, CreditCalculator.calculate(pricing, "video", "2K", null, 5, 1));
        // 缺档（4K 未配）回退基准 × 全局倍率：12 * 2.5 * 5 = 150
        assertEquals(150L, CreditCalculator.calculate(pricing, "video", "4K", null, 5, 1));
    }

    @Test
    void tieredImagePriceStillAppliesQualityMultiplier() {
        Map<String, Object> pricing = Map.of(
                "unit", "张",
                "sale", Map.of("output", "3 积分", "tiers", Map.of("2K", "6 积分")));
        assertEquals(6L, CreditCalculator.calculate(pricing, "image", "2K", "Low", null, 1));
        // 分档价上仍叠清晰度：6 * 2(High) = 12
        assertEquals(12L, CreditCalculator.calculate(pricing, "image", "2K", "High", null, 1));
        // 缺档（4K）回退基准 × 分辨率 × 清晰度：3 * 4 * 2 = 24
        assertEquals(24L, CreditCalculator.calculate(pricing, "image", "4K", "High", null, 1));
    }

    @Test
    void imageUsesResolutionAndQuality() {
        Map<String, Object> pricing = Map.of(
                "unit", "张",
                "sale", Map.of("output", "3 积分"));
        // 3 * 2(2K) * 2(High) * 1 = 12
        assertEquals(12L, CreditCalculator.calculate(pricing, "image", "2K", "High", null, 1));
    }

    @Test
    void ceilsFractionalResult() {
        Map<String, Object> pricing = Map.of(
                "unit", "秒",
                "sale", Map.of("output", "12 积分"));
        // 12 * 4s * 0.75(720P) * 1 = 36
        assertEquals(36L, CreditCalculator.calculate(pricing, "video", "720P", null, 4, 1));
    }

    @Test
    void audioCanChargeByThousandCharacters() {
        Map<String, Object> pricing = Map.of(
                "unit", "千字符",
                "sale", Map.of("output", "3 积分"));
        assertEquals(5L, CreditCalculator.calculate(pricing, "audio", null, null, 1500, 1));
    }

    @Test
    void audioCanChargeByTenThousandCharacters() {
        Map<String, Object> pricing = Map.of(
                "unit", "万字符",
                "sale", Map.of("output", "20 积分"));
        assertEquals(5L, CreditCalculator.calculate(pricing, "audio", null, null, 2500, 1));
    }

    @Test
    void billableCharactersCountsCjkAsTwo() {
        // 供应商口径（MiniMax）：1 汉字 = 2 字符，英文字母/数字/标点/空格各 1
        assertEquals(0, CreditCalculator.billableCharacters(null, 2));
        assertEquals(0, CreditCalculator.billableCharacters("", 2));
        assertEquals(8, CreditCalculator.billableCharacters("你好世界", 2));         // 4 汉字 ×2
        assertEquals(4, CreditCalculator.billableCharacters("abcd", 2));             // 4 字母 ×1
        assertEquals(7, CreditCalculator.billableCharacters("你好ab，", 2));         // 2 汉字×2 + 2 字母 + 全角逗号×1 = 7
        assertEquals(4, CreditCalculator.billableCharacters("，。！？", 2));          // 标点各 1
        // 1 万汉字 → 2 万计费字符 → 2 个「万字符」单位 → 2 × 46 积分
        Map<String, Object> pricing = Map.of("unit", "万字符", "sale", Map.of("output", "46 积分"));
        String tenThousandHanzi = "字".repeat(10_000);
        assertEquals(92L, CreditCalculator.calculate(
                pricing, "audio", null, null, CreditCalculator.billableCharacters(tenThousandHanzi, 2), 1));
    }

    @Test
    void billableCharactersFollowsConfiguredWeight() {
        // 火山/Ark 口径：1 字 = 1 字符
        assertEquals(4, CreditCalculator.billableCharacters("你好世界", 1));
        assertEquals(4, CreditCalculator.billableCharacters("abcd", 1));
        // 1 万汉字 → 1 万计费字符 → 1 个「万字符」单位 → 1 × 46 积分
        Map<String, Object> pricing = Map.of(
                "unit", "万字符", "sale", Map.of("output", "46 积分"), "cjkWeight", 1);
        String tenThousandHanzi = "字".repeat(10_000);
        assertEquals(46L, CreditCalculator.calculate(
                pricing, "audio", null, null,
                CreditCalculator.billableCharacters(tenThousandHanzi, 1), 1));
    }

    @Test
    void cjkWeightPrefersModelPricingThenFallsBackToProvider() {
        // 模型定价里显式配置优先
        assertEquals(1, CreditCalculator.cjkWeight(Map.of("cjkWeight", 1), "minimax-speech", "minimax", "speech-2.8-hd"));
        assertEquals(2, CreditCalculator.cjkWeight(Map.of("cjkWeight", 2), "ark-speech", "火山引擎", "doubao-seed-tts"));
        // 未配置时按供应商推断：MiniMax = 2，火山/Ark = 1
        assertEquals(2, CreditCalculator.cjkWeight(Map.of(), "minimax-speech", "minimax", "speech-2.8-hd"));
        assertEquals(2, CreditCalculator.cjkWeight(null, "minimax-speech", "minimax", "speech-2.8-hd"));
        assertEquals(1, CreditCalculator.cjkWeight(null, "ark-speech", "火山引擎", "doubao-seed-tts"));
        assertEquals(1, CreditCalculator.cjkWeight(null, null, "火山引擎", "doubao-seed-tts"));
        assertEquals(2, CreditCalculator.cjkWeight(null, null, "minimax", "speech-2.8-hd"));
    }

    @Test
    void explicitZeroIsFreeAndMissingPriceUsesDefault() {
        assertEquals(0L, CreditCalculator.calculate(
                Map.of("unit", "张", "sale", Map.of("output", "0")),
                "image", null, null, null, 1));
        assertEquals(12L, CreditCalculator.calculate(null, "video", null, null, 5, 1));
    }

    @Test
    void negativeStoredPriceCannotIncreaseBalance() {
        assertEquals(0L, CreditCalculator.calculate(
                Map.of("unit", "秒", "sale", Map.of("output", "-12")),
                "video", "1080P", null, 5, 1));
    }

    @Test
    void textPricingSeparatesCachedInputAndRoundsOnce() {
        Map<String, Object> pricing = Map.of(
                "unit", "千Token",
                "sale", Map.of("input", "2", "output", "8", "cache", "0.5"));
        // 普通输入 1000 * 2/千 + 输出 500 * 8/千 + 缓存 1000 * 0.5/千 = 6.5，最终向上取整。
        assertEquals(7L, CreditCalculator.calculateText(pricing, 2000, 500, 1000));
    }

    @Test
    void textPricingSupportsMillionTokensAndFlatCalls() {
        Map<String, Object> tokenPricing = Map.of(
                "unit", "百万Token",
                "sale", Map.of("input", "1", "output", "4", "cache", "0.1"));
        assertEquals(5L, CreditCalculator.calculateText(tokenPricing, 1_000_000, 1_000_000, 0));
        assertEquals(3L, CreditCalculator.calculateText(
                Map.of("unit", "次", "sale", Map.of("output", "3")), 99_999, 99_999, 0));
    }
}
