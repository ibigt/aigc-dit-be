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
