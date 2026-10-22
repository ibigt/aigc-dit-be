package cn.aigc.dit.server.pay;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.server.entity.RechargeOrder;
import com.wechat.pay.java.core.RSAAutoCertificateConfig;
import com.wechat.pay.java.core.notification.NotificationConfig;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.payments.nativepay.NativePayService;
import com.wechat.pay.java.service.payments.nativepay.model.Amount;
import com.wechat.pay.java.service.payments.nativepay.model.PrepayRequest;
import com.wechat.pay.java.service.payments.nativepay.model.PrepayResponse;
import com.wechat.pay.java.service.payments.nativepay.model.QueryOrderByOutTradeNoRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 微信支付 Native 扫码：prepay 返回 code_url（weixin:// 开头），
 * 支付结果由 notify 回调推送 + trade.query 查单兜底。
 * 注意：微信无沙箱环境，联调需真实商户号小额支付；回调验签依赖平台证书，
 * RSAAutoCertificateConfig 启动时会联网拉取平台证书，商户号未开通时不要 enabled。
 */
@Service
@ConditionalOnProperty(name = "pay.wechat.enabled", havingValue = "true")
public class WeChatChannel implements PaymentChannel {

    private final String mchId;
    private final String appId;
    private final String notifyUrl;
    private final NativePayService nativePayService;
    private final NotificationParser notificationParser;

    public WeChatChannel(
            @Value("${pay.wechat.mch-id}") String mchId,
            @Value("${pay.wechat.app-id}") String appId,
            @Value("${pay.wechat.merchant-serial-no}") String merchantSerialNo,
            @Value("${pay.wechat.private-key-path}") String privateKeyPath,
            @Value("${pay.wechat.api-v3-key}") String apiV3Key,
            @Value("${pay.wechat.notify-url}") String notifyUrl) {
        this.mchId = mchId;
        this.appId = appId;
        this.notifyUrl = notifyUrl;
        RSAAutoCertificateConfig config = new RSAAutoCertificateConfig.Builder()
                .merchantId(mchId)
                .privateKeyFromPath(privateKeyPath)
                .merchantSerialNumber(merchantSerialNo)
                .apiV3Key(apiV3Key)
                .build();
        this.nativePayService = new NativePayService.Builder().config(config).build();
        this.notificationParser = new NotificationParser((NotificationConfig) config);
    }

    @Override
    public String channelKey() {
        return "wechat";
    }

    @Override
    public String createQrCode(RechargeOrder order) {
        if (notifyUrl == null || notifyUrl.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "微信支付未配置回调地址（WXPAY_NOTIFY_URL）");
        }
        PrepayRequest request = new PrepayRequest();
        request.setAppid(appId);
        request.setMchid(mchId);
        request.setDescription("积分充值 " + order.getCreditAmount() + " 积分");
        request.setOutTradeNo(order.getOrderNo());
        request.setNotifyUrl(notifyUrl);
        Amount amount = new Amount();
        amount.setTotal(order.getAmountYuan() * 100); // 单位：分
        request.setAmount(amount);
        try {
            PrepayResponse response = nativePayService.prepay(request);
            return response.getCodeUrl();
        } catch (Exception e) { // SDK 的 Http/Validation/Service 异常统一兜底
            throw new BizException(ErrorCode.BAD_REQUEST, "微信下单失败：" + brief(e));
        }
    }

    @Override
    public String queryPaidTradeNo(RechargeOrder order) {
        QueryOrderByOutTradeNoRequest request = new QueryOrderByOutTradeNoRequest();
        request.setOutTradeNo(order.getOrderNo());
        request.setMchid(mchId);
        try {
            Transaction transaction = nativePayService.queryOrderByOutTradeNo(request);
            if (transaction.getTradeState() == Transaction.TradeStateEnum.SUCCESS) {
                return transaction.getTransactionId();
            }
            return null;
        } catch (Exception e) {
            return null; // 查单失败视为未支付，下一轮轮询重试
        }
    }

    /** 解析并验签微信支付回调（平台证书验签 + AEAD_AES_256_GCM 解密），失败抛 BizException。 */
    public Transaction parseNotify(String body, String serialNumber, String nonce, String signature, String timestamp) {
        RequestParam param = new RequestParam.Builder()
                .serialNumber(serialNumber)
                .nonce(nonce)
                .signature(signature)
                .timestamp(timestamp)
                .body(body)
                .build();
        try {
            return notificationParser.parse(param, Transaction.class);
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "微信回调验签失败");
        }
    }

    private static String brief(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank() || message.length() > 60 || message.contains("<")) {
            return "网络异常，请稍后重试";
        }
        return message;
    }
}
