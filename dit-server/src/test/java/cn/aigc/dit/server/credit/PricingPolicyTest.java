package cn.aigc.dit.server.credit;

import cn.aigc.dit.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PricingPolicyTest {
    @Test
    void acceptsZeroAndCategorySpecificUnit() {
        assertDoesNotThrow(() -> PricingPolicy.validate("video", Map.of(
                "unit", "秒", "sale", Map.of("output", "0 积分"))));
    }

    @Test
    void rejectsNegativePriceAndMismatchedUnit() {
        assertThrows(BizException.class, () -> PricingPolicy.validate("image", Map.of(
                "unit", "张", "sale", Map.of("output", "-1"))));
        assertThrows(BizException.class, () -> PricingPolicy.validate("audio", Map.of(
                "unit", "秒", "sale", Map.of("output", "3"))));
    }

    @Test
    void rejectsATierWhosePriceIsBelowItsCost() {
        // 能力之间不分价：任一档售价低于该档成本，用户选哪个能力都会亏本 → 拦截
        BizException error = assertThrows(BizException.class, () -> PricingPolicy.validate("video", Map.of(
                "unit", "秒",
                "sale", Map.of("output", "33 积分", "tiers", Map.of("480P", "5", "720P", "15", "1080P", "33")),
                "cost", Map.of("output", "¥2.48", "tiers", Map.of("480P", "¥0.51", "720P", "¥1.1", "1080P", "¥2.48")))));
        assertTrue(error.getMessage().contains("480P"));
        assertTrue(error.getMessage().contains("亏本"));
    }

    @Test
    void acceptsTiersThatCoverTheirCost() {
        // 5 积分 = ¥0.5 ≥ ¥0.51 吗？0.5 < 0.51 → 刚好拦住；改成 6 积分即通过
        assertDoesNotThrow(() -> PricingPolicy.validate("video", Map.of(
                "unit", "秒",
                "sale", Map.of("output", "33 积分", "tiers", Map.of("480P", "6", "720P", "15", "1080P", "33")),
                "cost", Map.of("output", "¥2.48", "tiers", Map.of("480P", "¥0.51", "720P", "¥1.1", "1080P", "¥2.48")))));
    }

    @Test
    void rejectsABasePriceBelowItsCost() {
        BizException error = assertThrows(BizException.class, () -> PricingPolicy.validate("video", Map.of(
                "unit", "秒",
                "sale", Map.of("output", "3 积分"),
                "cost", Map.of("output", "¥0.5"))));
        assertTrue(error.getMessage().contains("亏本"));
    }
}
