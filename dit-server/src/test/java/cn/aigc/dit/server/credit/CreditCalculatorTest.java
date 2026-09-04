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
}
