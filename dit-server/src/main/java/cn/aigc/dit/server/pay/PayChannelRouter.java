package cn.aigc.dit.server.pay;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.server.entity.RechargeOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 渠道路由。pay.mock.enabled=true（默认）时统一走 Mock 二维码，
 * 接入真实渠道后置 false，按订单渠道选择已注册的 PaymentChannel。
 */
@Service
public class PayChannelRouter {

    private static final Logger log = LoggerFactory.getLogger(PayChannelRouter.class);

    private final List<PaymentChannel> channels;
    private final boolean mockEnabled;

    public PayChannelRouter(
            List<PaymentChannel> channels,
            @Value("${pay.mock.enabled:true}") boolean mockEnabled) {
        this.channels = channels == null ? List.of() : channels;
        this.mockEnabled = mockEnabled;
    }

    public boolean isMockEnabled() {
        return mockEnabled;
    }

    public String qrCodeFor(RechargeOrder order) {
        if (mockEnabled) {
            return "https://mock.pay.local/recharge/" + order.getOrderNo()
                    + "?yuan=" + order.getAmountYuan();
        }
        return channels.stream()
                .filter(channel -> channel.channelKey().equalsIgnoreCase(order.getPaymentChannel()))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "支付渠道暂未开通"))
                .createQrCode(order);
    }

    /** 查单：渠道已支付返回渠道交易号；未支付/渠道未注册/查询失败返回 null，不打断轮询。 */
    public String queryPaidTradeNo(RechargeOrder order) {
        if (mockEnabled) {
            return null;
        }
        return channels.stream()
                .filter(channel -> channel.channelKey().equalsIgnoreCase(order.getPaymentChannel()))
                .findFirst()
                .map(channel -> {
                    try {
                        return channel.queryPaidTradeNo(order);
                    } catch (RuntimeException e) {
                        log.warn("查询支付状态失败 orderNo={} channel={}", order.getOrderNo(), channel.channelKey(), e);
                        return null;
                    }
                })
                .orElse(null);
    }
}
