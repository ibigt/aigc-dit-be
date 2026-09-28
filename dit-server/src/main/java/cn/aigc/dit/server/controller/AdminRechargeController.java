package cn.aigc.dit.server.controller;

import cn.aigc.dit.common.model.resp.CommonResult;
import cn.aigc.dit.common.model.resp.PageQueryResp;
import cn.aigc.dit.server.model.resp.AdminRechargeOrderResp;
import cn.aigc.dit.server.model.resp.AdminRechargeSummaryResp;
import cn.aigc.dit.server.service.AdminRechargeService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/recharges")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminRechargeController {
    private final AdminRechargeService adminRechargeService;

    @GetMapping
    public CommonResult<PageQueryResp<AdminRechargeOrderResp>> page(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String payment) {
        int safePage = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int safeSize = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
        return CommonResult.success(adminRechargeService.page(safePage, safeSize, status, payment));
    }

    @GetMapping("/summary")
    public CommonResult<AdminRechargeSummaryResp> summary() {
        return CommonResult.success(adminRechargeService.summary());
    }
}
