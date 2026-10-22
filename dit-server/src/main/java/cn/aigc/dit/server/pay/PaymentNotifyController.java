package cn.aigc.dit.server.pay;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.server.service.CreditService;
import com.alipay.api.internal.util.AlipaySignature;
import com.wechat.pay.java.service.payments.model.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * 支付渠道异步回调入口（免登录，安全靠验签）。
 * 微信按 APIv3 规范应答 JSON；支付宝按旧规范应答纯文本 success/failure。
 * 渠道会按失败应答重试通知，因此「订单不存在/金额不符」这类不可恢复问题
 * 返回成功应答停止重试（记 error 日志人工核对），瞬时异常返回失败让渠道重试。
 */
@RestController
@RequestMapping("/api/credits/recharge")
public class PaymentNotifyController {

    private static final Logger log = LoggerFactory.getLogger(PaymentNotifyController.class);
    private static final Set<String> ALIPAY_PAID_STATUSES = Set.of("TRADE_SUCCESS", "TRADE_FINISHED");

    private final CreditService creditService;
    private final ObjectProvider<WeChatChannel> weChatChannel;
    private final String alipayPublicKey;

    public PaymentNotifyController(
            CreditService creditService,
            ObjectProvider<WeChatChannel> weChatChannel,
            @Value("${pay.alipay.alipay-public-key:}") String alipayPublicKey) {
        this.creditService = creditService;
        this.weChatChannel = weChatChannel;
        this.alipayPublicKey = alipayPublicKey;
    }

    @PostMapping(value = "/wechat/notify", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> wechatNotify(
            @RequestBody String body,
            @RequestHeader(value = "Wechatpay-Serial", required = false) String serial,
            @RequestHeader(value = "Wechatpay-Nonce", required = false) String nonce,
            @RequestHeader(value = "Wechatpay-Signature", required = false) String signature,
            @RequestHeader(value = "Wechatpay-Timestamp", required = false) String timestamp) {
        WeChatChannel channel = weChatChannel.getIfAvailable();
        if (channel == null) {
            return respond(HttpStatus.SERVICE_UNAVAILABLE, "FAIL", "微信渠道未启用");
        }
        Transaction transaction = channel.parseNotify(body, serial, nonce, signature, timestamp);
        if (transaction.getTradeState() != Transaction.TradeStateEnum.SUCCESS) {
            return ok("SUCCESS", "已接收非支付成功通知");
        }
        Integer amountFen = transaction.getAmount() == null ? null : transaction.getAmount().getTotal();
        return settle(transaction.getOutTradeNo(), transaction.getTransactionId(), amountFen, "（微信回调）");
    }

    @PostMapping(value = "/alipay/notify", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> alipayNotify(@RequestParam Map<String, String> params) {
        try {
            if (!AlipaySignature.rsaCheckV1(params, alipayPublicKey, "UTF-8", "RSA2")) {
                log.warn("支付宝回调验签失败 params={}", params);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("failure");
            }
        } catch (Exception e) {
            log.error("支付宝回调验签异常", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("failure");
        }
        String tradeStatus = params.get("trade_status");
        if (!ALIPAY_PAID_STATUSES.contains(tradeStatus)) {
            return ResponseEntity.ok("success");
        }
        Integer amountFen = params.get("total_amount") == null ? null
                : new BigDecimal(params.get("total_amount")).movePointRight(2).intValueExact();
        ResponseEntity<Map<String, String>> result = settle(params.get("out_trade_no"), params.get("trade_no"), amountFen, "（支付宝回调）");
        return ResponseEntity.status(result.getStatusCode())
                .body("SUCCESS".equals(result.getBody().get("code")) ? "success" : "failure");
    }

    private ResponseEntity<Map<String, String>> settle(String orderNo, String tradeNo, Integer amountFen, String remark) {
        try {
            creditService.settleByNotify(orderNo, tradeNo, amountFen, remark);
            return ok("SUCCESS", "成功");
        } catch (BizException e) {
            // 订单不存在/金额不符：不可恢复，返回成功应答停止渠道重试，留日志人工核对
            if (e.getCode() == ErrorCode.NOT_FOUND || e.getCode() == ErrorCode.BAD_REQUEST) {
                log.error("支付回调入账被拒 orderNo={} tradeNo={} amount={} 原因={}", orderNo, tradeNo, amountFen, e.getMessage());
                return ok("SUCCESS", "已接收但未入账，请人工核对");
            }
            throw e;
        }
    }

    private ResponseEntity<Map<String, String>> ok(String code, String message) {
        return respond(HttpStatus.OK, code, message);
    }

    private ResponseEntity<Map<String, String>> respond(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }
}
