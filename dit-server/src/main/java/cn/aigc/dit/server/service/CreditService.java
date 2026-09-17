package cn.aigc.dit.server.service;

import cn.aigc.dit.common.constant.CreditReason;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.common.model.resp.PageQueryResp;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.server.entity.CreditLedger;
import cn.aigc.dit.server.entity.SysUser;
import cn.aigc.dit.server.mapper.CreditLedgerMapper;
import cn.aigc.dit.server.mapper.SysUserMapper;
import cn.aigc.dit.server.model.resp.CreditLedgerResp;
import cn.aigc.dit.server.model.resp.CreditSummaryResp;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CreditService {

    public static final int YUAN_TO_CREDITS = 10;

    private final SysUserMapper sysUserMapper;
    private final CreditLedgerMapper creditLedgerMapper;

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
    public long recharge(int amountYuan, String payment) {
        if (amountYuan <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "充值金额无效");
        }
        long credits = (long) amountYuan * YUAN_TO_CREDITS;
        Long userId = UserContext.requireUserId();
        String channel = "alipay".equalsIgnoreCase(payment) ? "支付宝" : "微信支付";
        return change(userId, credits, CreditReason.RECHARGE, null,
                "Mock 充值 ¥" + amountYuan + " · " + channel);
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
