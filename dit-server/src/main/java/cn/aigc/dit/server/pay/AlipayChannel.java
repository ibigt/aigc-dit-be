package cn.aigc.dit.server.pay;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.server.entity.RechargeOrder;
import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.AlipayRequest;
import com.alipay.api.AlipayResponse;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.request.AlipayTradePrecreateRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.response.AlipayTradePrecreateResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 支付宝当面付（扫码支付）：precreate 预下单返回 qr_code，
 * 用户扫码支付后由 trade.query 查单确认（notify 回调可作为后续增强）。
 * 沙箱与正式环境仅网关和密钥不同，切换走配置。
 */
@Service
@ConditionalOnProperty(name = "pay.alipay.enabled", havingValue = "true")
public class AlipayChannel implements PaymentChannel {

    private static final Set<String> PAID_STATUSES = Set.of("TRADE_SUCCESS", "TRADE_FINISHED");

    private final AlipayClient client;

    public AlipayChannel(
            @Value("${pay.alipay.app-id}") String appId,
            @Value("${pay.alipay.merchant-private-key}") String merchantPrivateKey,
            @Value("${pay.alipay.alipay-public-key}") String alipayPublicKey,
            @Value("${pay.alipay.gateway}") String gateway) {
        this.client = new DefaultAlipayClient(gateway, appId, merchantPrivateKey, "json", "UTF-8", alipayPublicKey, "RSA2");
    }

    @Override
    public String channelKey() {
        return "alipay";
    }

    @Override
    public String createQrCode(RechargeOrder order) {
        AlipayTradePrecreateRequest request = new AlipayTradePrecreateRequest();
        request.setBizContent("{\"out_trade_no\":\"" + order.getOrderNo() + "\","
                + "\"total_amount\":\"" + order.getAmountYuan() + ".00\","
                + "\"subject\":\"积分充值 " + order.getCreditAmount() + " 积分\"}");
        try {
            // 同一 out_trade_no 的 precreate 幂等，沙箱网关偶发 404/超时时自动重试一次
            AlipayTradePrecreateResponse response = executeWithRetry(request);
            if (!response.isSuccess()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "支付宝下单失败：" + orDefault(response.getSubMsg(), response.getMsg()));
            }
            return response.getQrCode();
        } catch (AlipayApiException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "支付宝下单失败：" + brief(e));
        }
    }

    @Override
    public String queryPaidTradeNo(RechargeOrder order) {
        AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
        request.setBizContent("{\"out_trade_no\":\"" + order.getOrderNo() + "\"}");
        try {
            AlipayTradeQueryResponse response = client.execute(request);
            if (response.isSuccess() && PAID_STATUSES.contains(response.getTradeStatus())) {
                return response.getTradeNo();
            }
            return null;
        } catch (AlipayApiException e) {
            return null; // 查单失败视为未支付，下一轮轮询重试
        }
    }

    private <T extends AlipayResponse> T executeWithRetry(AlipayRequest<T> request) throws AlipayApiException {
        AlipayApiException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return client.execute(request);
            } catch (AlipayApiException e) {
                last = e;
            }
        }
        throw last;
    }

    /** 网络异常时 SDK 会把整页 HTML 错误页塞进 message，转成可读文案。 */
    private static String brief(AlipayApiException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank() || message.length() > 60 || message.contains("<")) {
            return "网络异常，请稍后重试";
        }
        return message;
    }

    private static String orDefault(String subMsg, String msg) {
        return subMsg != null && !subMsg.isBlank() ? subMsg : msg;
    }
}
