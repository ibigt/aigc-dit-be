package cn.aigc.dit.server.controller;

import cn.aigc.dit.common.model.resp.CommonResult;
import cn.aigc.dit.common.model.resp.PageQueryResp;
import cn.aigc.dit.server.model.req.CreditRechargeReq;
import cn.aigc.dit.server.model.resp.CreditLedgerResp;
import cn.aigc.dit.server.model.resp.CreditSummaryResp;
import cn.aigc.dit.server.model.resp.UserProfileResp;
import cn.aigc.dit.server.service.AuthService;
import cn.aigc.dit.server.service.CreditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "积分")
@RestController
@RequestMapping("/api/credits")
@RequiredArgsConstructor
public class CreditController {

    private final CreditService creditService;
    private final AuthService authService;

    @Operation(summary = "积分汇总")
    @GetMapping("/summary")
    public CommonResult<CreditSummaryResp> summary() {
        return CommonResult.success(creditService.summary());
    }

    @Operation(summary = "积分流水")
    @GetMapping("/ledger")
    public CommonResult<PageQueryResp<CreditLedgerResp>> ledger(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "50") Integer pageSize) {
        return CommonResult.success(creditService.pageMine(pageNum, pageSize));
    }

    @Operation(summary = "Mock 充值（1 元 = 10 积分）")
    @PostMapping("/recharge")
    public CommonResult<UserProfileResp> recharge(@Valid @RequestBody CreditRechargeReq req) {
        creditService.recharge(req.getAmountYuan(), req.getPayment());
        return CommonResult.success(authService.currentUser());
    }
}
