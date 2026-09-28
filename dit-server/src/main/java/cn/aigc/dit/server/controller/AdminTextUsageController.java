package cn.aigc.dit.server.controller;

import cn.aigc.dit.common.model.resp.CommonResult;
import cn.aigc.dit.common.model.resp.PageQueryResp;
import cn.aigc.dit.server.model.resp.AdminTextUsageResp;
import cn.aigc.dit.server.model.resp.AdminTextUsageSummaryResp;
import cn.aigc.dit.server.service.AdminTextUsageService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/text-usage")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminTextUsageController {

    private final AdminTextUsageService service;

    @GetMapping
    public CommonResult<PageQueryResp<AdminTextUsageResp>> page(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(defaultValue = "30") Integer days,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) Long modelId,
            @RequestParam(required = false) String keyword) {
        return CommonResult.success(service.page(
                positive(pageNum, 1), Math.min(positive(pageSize, 20), 100), boundedDays(days),
                operation, modelId, keyword));
    }

    @GetMapping("/summary")
    public CommonResult<AdminTextUsageSummaryResp> summary(
            @RequestParam(defaultValue = "30") Integer days,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) Long modelId,
            @RequestParam(required = false) String keyword) {
        return CommonResult.success(service.summary(boundedDays(days), operation, modelId, keyword));
    }

    private static int positive(Integer value, int fallback) {
        return value == null || value < 1 ? fallback : value;
    }

    private static int boundedDays(Integer value) {
        return Math.min(positive(value, 30), 365);
    }
}
