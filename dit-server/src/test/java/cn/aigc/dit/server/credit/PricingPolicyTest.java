package cn.aigc.dit.server.credit;

import cn.aigc.dit.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
