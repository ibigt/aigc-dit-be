package cn.aigc.dit.server.credit;

import cn.aigc.dit.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CreditRechargePolicyTest {

    @Test
    void acceptsConfiguredPackagesAndChannels() {
        assertDoesNotThrow(() -> CreditRechargePolicy.validate(10, "wechat", "request-1"));
        assertDoesNotThrow(() -> CreditRechargePolicy.validate(200, "alipay", "request-2"));
    }

    @Test
    void rejectsUnsupportedAmountChannelAndMissingRequestId() {
        assertThrows(BizException.class, () -> CreditRechargePolicy.validate(20, "wechat", "request-1"));
        assertThrows(BizException.class, () -> CreditRechargePolicy.validate(10, "cash", "request-1"));
        assertThrows(BizException.class, () -> CreditRechargePolicy.validate(10, "wechat", ""));
    }
}
