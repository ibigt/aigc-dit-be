package cn.aigc.dit.server.pay;

import cn.aigc.dit.server.entity.RechargeOrder;

/** 支付渠道适配器；接入支付宝/微信时各提供一个实现并注册为 Spring Bean。 */
public interface PaymentChannel {

    /** 支持的渠道 key，对应 recharge_order.payment_channel（alipay / wechat）。 */
    String channelKey();

    /** 创建支付二维码内容（支付宝当面付 code_url / 微信 Native code_url）。 */
    String createQrCode(RechargeOrder order);

    /** 查单：已支付则返回渠道交易号（如支付宝 trade_no），未支付/不支持查单返回 null。 */
    default String queryPaidTradeNo(RechargeOrder order) {
        return null;
    }
}
