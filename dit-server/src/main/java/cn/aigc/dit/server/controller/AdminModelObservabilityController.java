package cn.aigc.dit.server.controller;

import cn.aigc.dit.common.model.resp.CommonResult;
import cn.aigc.dit.server.model.resp.AdminModelObservabilityResp;
import cn.aigc.dit.server.service.AdminModelObservabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminModelObservabilityController {
    private final AdminModelObservabilityService service;

    @GetMapping("/api/admin/model-observability")
    public CommonResult<AdminModelObservabilityResp> get(
            @RequestParam(required = false) Integer days,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Long modelId) {
        return CommonResult.success(service.get(days, category, modelId));
    }
}
