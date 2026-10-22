package cn.aigc.dit.server.service;

import cn.aigc.dit.common.constant.CreditReason;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.common.model.resp.PageQueryResp;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.server.entity.CreditLedger;
import cn.aigc.dit.server.entity.RechargeOrder;
import cn.aigc.dit.server.entity.SysUser;
import cn.aigc.dit.server.mapper.CreditLedgerMapper;
import cn.aigc.dit.server.mapper.RechargeOrderMapper;
import cn.aigc.dit.server.mapper.SysUserMapper;
import cn.aigc.dit.server.credit.CreditRechargePolicy;
import cn.aigc.dit.server.model.resp.CreditLedgerResp;
import cn.aigc.dit.server.model.resp.CreditSummaryResp;
import cn.aigc.dit.server.model.resp.RechargeOrderResp;
import cn.aigc.dit.server.pay.PayChannelRouter;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CreditService {

    public static final int YUAN_TO_CREDITS = 10;

    private final SysUserMapper sysUserMapper;
    private final CreditLedgerMapper creditLedgerMapper;
    private final RechargeOrderMapper rechargeOrderMapper;
    private final PayChannelRouter payChannelRouter;
    private final TransactionTemplate transactionTemplate;

    public long balance(Long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || user.getCreditBalance() == null) {
            return 0L;
        }
        return user.getCreditBalance();
    }

    @Transactional
    public long change(Long userId, long delta, String reason, String refId, String remark) {
        if (delta == 0) {
            return balance(userId);
        }
        int updated = sysUserMapper.addBalance(userId, delta);
        if (updated == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, delta < 0 ? "积分不足" : "积分变更失败");
        }
        long after = balance(userId);
        CreditLedger ledger = new CreditLedger();
        ledger.setUserId(userId);
        ledger.setChangeAmt(delta);
        ledger.setBalanceAfter(after);
        ledger.setReason(reason);
        ledger.setRefId(refId);
        ledger.setRemark(remark);
        creditLedgerMapper.insert(ledger);
        return after;
    }

    @Transactional
    public long recharge(int amountYuan, String payment, String requestId) {
        CreditRechargePolicy.validate(amountYuan, payment, requestId);
        long credits = (long) amountYuan * YUAN_TO_CREDITS;
        Long userId = UserContext.requireUserId();
        RechargeOrder existing = rechargeOrderMapper.selectOne(new LambdaQueryWrapper<RechargeOrder>()
                .eq(RechargeOrder::getUserId, userId)
                .eq(RechargeOrder::getRequestId, requestId)
                .last("LIMIT 1"));
        if (existing != null || creditLedgerMapper.sumRechargeByUserAndRef(userId, requestId) > 0) {
            return balance(userId);
        }
        String channel = "alipay".equalsIgnoreCase(payment) ? "支付宝" : "微信支付";
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        RechargeOrder order = new RechargeOrder();
        order.setOrderNo("RC" + now.toInstant().toEpochMilli() + UUID.randomUUID().toString().substring(0, 8));
        order.setRequestId(requestId);
        order.setUserId(userId);
        order.setAmountYuan(amountYuan);
        order.setCreditAmount(credits);
        order.setPaymentChannel(payment.toLowerCase());
        order.setStatus("PAID");
        order.setProviderTradeNo("mock-" + UUID.randomUUID());
        order.setPaidAt(now);
        if (rechargeOrderMapper.insertIfAbsent(order) == 0) {
            return balance(userId);
        }
        return change(userId, credits, CreditReason.RECHARGE, requestId,
                "Mock 充值 ¥" + amountYuan + " · " + channel);
    }

    /** 创建充值订单：返回支付二维码内容，等待用户扫码支付（状态 PAYING）。 */
    @Transactional
    public RechargeOrderResp createRechargeOrder(int amountYuan, String payment, String requestId) {
        CreditRechargePolicy.validate(amountYuan, payment, requestId);
        long credits = (long) amountYuan * YUAN_TO_CREDITS;
        Long userId = UserContext.requireUserId();
        RechargeOrder existing = findOrderByRequest(userId, requestId);
        if (existing != null) {
            return toOrderResp(existing);
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        RechargeOrder order = new RechargeOrder();
        order.setOrderNo("RC" + now.toInstant().toEpochMilli() + UUID.randomUUID().toString().substring(0, 8));
        order.setRequestId(requestId);
        order.setUserId(userId);
        order.setAmountYuan(amountYuan);
        order.setCreditAmount(credits);
        order.setPaymentChannel(payment.toLowerCase());
        order.setStatus("PAYING");
        order.setQrCode(payChannelRouter.qrCodeFor(order));
        if (rechargeOrderMapper.insertIfAbsent(order) == 0) {
            return toOrderResp(findOrderByRequest(userId, requestId));
        }
        return toOrderResp(order);
    }

    public RechargeOrderResp getRechargeOrder(String orderNo) {
        RechargeOrder order = requireOwnOrder(orderNo);
        // 真实渠道下轮询即查单：用户扫码支付成功后由 trade.query 结果触发入账（notify 回调上线前的兜底路径）
        if ("PAYING".equals(order.getStatus()) && !payChannelRouter.isMockEnabled()) {
            String tradeNo = payChannelRouter.queryPaidTradeNo(order);
            if (tradeNo != null) {
                settleOrder(order, tradeNo, "（扫码支付）");
                order = requireOwnOrder(orderNo);
            }
        }
        return toOrderResp(order);
    }

    /** 模拟渠道支付回调：仅 pay.mock.enabled 时可用，把 PAYING 订单入账。 */
    @Transactional
    public RechargeOrderResp mockPayRechargeOrder(String orderNo) {
        if (!payChannelRouter.isMockEnabled()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "模拟支付未开启");
        }
        RechargeOrder order = requireOwnOrder(orderNo);
        if ("PAID".equals(order.getStatus())) {
            return toOrderResp(order);
        }
        if (!"PAYING".equals(order.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "订单状态不允许支付");
        }
        settleOrder(order, "mock-" + UUID.randomUUID(), "（Mock 回调）");
        return toOrderResp(requireOwnOrder(orderNo));
    }

    /** 条件更新入账：仅当订单仍为 PAYING 时置 PAID 并加积分，重复回调/并发下天然幂等；两步同事务防半途失败。 */
    private void settleOrder(RechargeOrder order, String providerTradeNo, String remarkSuffix) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        Integer settled = transactionTemplate.execute(tx -> rechargeOrderMapper.update(null, new LambdaUpdateWrapper<RechargeOrder>()
                .eq(RechargeOrder::getId, order.getId())
                .eq(RechargeOrder::getStatus, "PAYING")
                .set(RechargeOrder::getStatus, "PAID")
                .set(RechargeOrder::getProviderTradeNo, providerTradeNo)
                .set(RechargeOrder::getPaidAt, now)
                .set(RechargeOrder::getUpdatedAt, now)));
        if (settled == null || settled == 0) {
            return;
        }
        change(order.getUserId(), order.getCreditAmount(), CreditReason.RECHARGE, order.getRequestId(),
                "充值 ¥" + order.getAmountYuan() + " · " + channelLabel(order.getPaymentChannel()) + remarkSuffix);
    }

    private RechargeOrder findOrderByRequest(Long userId, String requestId) {
        return rechargeOrderMapper.selectOne(new LambdaQueryWrapper<RechargeOrder>()
                .eq(RechargeOrder::getUserId, userId)
                .eq(RechargeOrder::getRequestId, requestId)
                .last("LIMIT 1"));
    }

    private RechargeOrder requireOwnOrder(String orderNo) {
        Long userId = UserContext.requireUserId();
        RechargeOrder order = rechargeOrderMapper.selectOne(new LambdaQueryWrapper<RechargeOrder>()
                .eq(RechargeOrder::getOrderNo, orderNo)
                .last("LIMIT 1"));
        if (order == null || !userId.equals(order.getUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "充值订单不存在");
        }
        return order;
    }

    private static String channelLabel(String payment) {
        return "alipay".equalsIgnoreCase(payment) ? "支付宝" : "微信支付";
    }

    private static RechargeOrderResp toOrderResp(RechargeOrder order) {
        RechargeOrderResp resp = new RechargeOrderResp();
        resp.setOrderNo(order.getOrderNo());
        resp.setAmountYuan(order.getAmountYuan());
        resp.setCreditAmount(order.getCreditAmount());
        resp.setPayment(order.getPaymentChannel());
        resp.setStatus(order.getStatus());
        resp.setQrCode(order.getQrCode());
        resp.setPaidAt(order.getPaidAt());
        return resp;
    }

    public PageQueryResp<CreditLedgerResp> pageMine(int pageNum, int pageSize) {
        Long userId = UserContext.requireUserId();
        Page<CreditLedger> page = new Page<>(pageNum, pageSize);
        creditLedgerMapper.selectPage(page, new LambdaQueryWrapper<CreditLedger>()
                .eq(CreditLedger::getUserId, userId)
                .orderByDesc(CreditLedger::getCreatedAt));
        List<CreditLedgerResp> result = page.getRecords().stream().map(this::toResp).toList();
        return PageQueryResp.of(page.getCurrent(), page.getSize(), page.getTotal(), result);
    }

    public CreditSummaryResp summary() {
        Long userId = UserContext.requireUserId();
        OffsetDateTime monthStart = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"))
                .withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        List<CreditLedger> all = creditLedgerMapper.selectList(new LambdaQueryWrapper<CreditLedger>()
                .eq(CreditLedger::getUserId, userId));
        long recharge = 0;
        long monthSpent = 0;
        for (CreditLedger item : all) {
            if (item.getChangeAmt() != null && item.getChangeAmt() > 0
                    && CreditReason.RECHARGE.equals(item.getReason())) {
                recharge += item.getChangeAmt();
            }
            if (item.getChangeAmt() != null && item.getChangeAmt() < 0
                    && item.getCreatedAt() != null && !item.getCreatedAt().isBefore(monthStart)) {
                monthSpent += -item.getChangeAmt();
            }
        }
        CreditSummaryResp resp = new CreditSummaryResp();
        resp.setBalance(balance(userId));
        resp.setTotalRecharge(recharge);
        resp.setMonthSpent(monthSpent);
        return resp;
    }

    public long netByRef(String refId) {
        if (refId == null || refId.isBlank()) {
            return 0L;
        }
        return creditLedgerMapper.sumByRefId(refId);
    }

    public boolean hasRefund(String refId) {
        return netByRef(refId) >= 0;
    }

    private CreditLedgerResp toResp(CreditLedger ledger) {
        CreditLedgerResp resp = new CreditLedgerResp();
        resp.setId(ledger.getId());
        resp.setChangeAmt(ledger.getChangeAmt());
        resp.setBalanceAfter(ledger.getBalanceAfter());
        resp.setReason(ledger.getReason());
        resp.setRefId(ledger.getRefId());
        resp.setRemark(ledger.getRemark());
        resp.setCreatedAt(ledger.getCreatedAt());
        boolean charge = ledger.getChangeAmt() != null && ledger.getChangeAmt() > 0;
        resp.setType(charge ? "charge" : "deduct");
        resp.setTitle(ledger.getRemark() == null || ledger.getRemark().isBlank()
                ? (charge ? "积分充值" : "积分扣减")
                : ledger.getRemark());
        return resp;
    }
}
