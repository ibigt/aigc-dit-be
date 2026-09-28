package cn.aigc.dit.server.credit;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;

import java.util.Set;

public final class CreditRechargePolicy {

    public static final Set<Integer> PACKAGES_YUAN = Set.of(10, 50, 100, 200);

    private CreditRechargePolicy() {
    }

    public static void validate(int amountYuan, String payment, String requestId) {
        if (!PACKAGES_YUAN.contains(amountYuan)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择有效的充值套餐");
        }
        if (!"wechat".equalsIgnoreCase(payment) && !"alipay".equalsIgnoreCase(payment)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持的支付方式");
        }
        if (requestId == null || requestId.isBlank() || requestId.length() > 64) {
            throw new BizException(ErrorCode.BAD_REQUEST, "充值请求号无效");
        }
    }
}
