package cn.aigc.dit.server.service;

import cn.aigc.dit.common.model.resp.PageQueryResp;
import cn.aigc.dit.server.entity.RechargeOrder;
import cn.aigc.dit.server.entity.SysUser;
import cn.aigc.dit.server.mapper.CreditLedgerMapper;
import cn.aigc.dit.server.mapper.RechargeOrderMapper;
import cn.aigc.dit.server.mapper.SysUserMapper;
import cn.aigc.dit.server.model.resp.AdminRechargeOrderResp;
import cn.aigc.dit.server.model.resp.AdminRechargeSummaryResp;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminRechargeService {
    private final RechargeOrderMapper rechargeOrderMapper;
    private final CreditLedgerMapper creditLedgerMapper;
    private final SysUserMapper sysUserMapper;

    public PageQueryResp<AdminRechargeOrderResp> page(int pageNum, int pageSize, String status, String payment) {
        Page<RechargeOrder> page = new Page<>(pageNum, pageSize);
        rechargeOrderMapper.selectPage(page, new LambdaQueryWrapper<RechargeOrder>()
                .eq(StringUtils.hasText(status), RechargeOrder::getStatus, status)
                .eq(StringUtils.hasText(payment), RechargeOrder::getPaymentChannel, payment)
                .orderByDesc(RechargeOrder::getCreatedAt));
        List<AdminRechargeOrderResp> result = page.getRecords().stream().map(this::toResp).toList();
        return PageQueryResp.of(page.getCurrent(), page.getSize(), page.getTotal(), result);
    }

    public AdminRechargeSummaryResp summary() {
        List<RechargeOrder> orders = rechargeOrderMapper.selectList(new LambdaQueryWrapper<RechargeOrder>()
                .orderByDesc(RechargeOrder::getCreatedAt));
        AdminRechargeSummaryResp resp = new AdminRechargeSummaryResp();
        long paid = 0;
        long credited = 0;
        long exceptions = 0;
        for (RechargeOrder order : orders) {
            if ("PAID".equals(order.getStatus())) paid += order.getAmountYuan();
            long ledger = creditLedgerMapper.sumRechargeByUserAndRef(order.getUserId(), order.getRequestId());
            credited += ledger;
            if (ledger != order.getCreditAmount()) exceptions++;
        }
        resp.setOrderCount(orders.size());
        resp.setPaidAmountYuan(paid);
        resp.setCreditedAmount(credited);
        resp.setExceptionCount(exceptions);
        return resp;
    }

    private AdminRechargeOrderResp toResp(RechargeOrder order) {
        AdminRechargeOrderResp resp = new AdminRechargeOrderResp();
        resp.setId(order.getId());
        resp.setOrderNo(order.getOrderNo());
        resp.setUserId(order.getUserId());
        SysUser user = sysUserMapper.selectById(order.getUserId());
        if (user != null) {
            resp.setUsername(user.getUsername());
            resp.setEmail(user.getEmail());
        }
        resp.setAmountYuan(order.getAmountYuan());
        resp.setCreditAmount(order.getCreditAmount());
        resp.setPaymentChannel(order.getPaymentChannel());
        resp.setStatus(order.getStatus());
        resp.setProviderTradeNo(order.getProviderTradeNo());
        long ledger = creditLedgerMapper.sumRechargeByUserAndRef(order.getUserId(), order.getRequestId());
        resp.setLedgerAmount(ledger);
        resp.setReconciliationStatus(ledger == order.getCreditAmount() ? "MATCHED" : (ledger == 0 ? "MISSING_LEDGER" : "AMOUNT_MISMATCH"));
        resp.setPaidAt(order.getPaidAt());
        resp.setCreatedAt(order.getCreatedAt());
        return resp;
    }
}
