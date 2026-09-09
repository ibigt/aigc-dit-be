package cn.aigc.dit.server.controller;

import cn.aigc.dit.common.model.resp.CommonResult;
import cn.aigc.dit.server.model.req.AdminModelEnabledReq;
import cn.aigc.dit.server.model.req.AdminModelSaveReq;
import cn.aigc.dit.server.model.resp.AdminModelResp;
import cn.aigc.dit.server.service.AdminModelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "管理端-模型")
@RestController
@RequestMapping("/api/admin/models")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminModelController {

    private final AdminModelService adminModelService;

    @Operation(summary = "已接入的适配协议")
    @GetMapping("/adapters")
    public CommonResult<List<Map<String, Object>>> adapters() {
        return CommonResult.success(adminModelService.listAdapters());
    }

    @Operation(summary = "模型列表")
    @GetMapping
    public CommonResult<List<AdminModelResp>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String provider) {
        return CommonResult.success(adminModelService.listAdmin(keyword, category, provider));
    }

    @Operation(summary = "接入模型")
    @PostMapping
    public CommonResult<AdminModelResp> create(@Valid @RequestBody AdminModelSaveReq req) {
        return CommonResult.success(adminModelService.create(req));
    }

    @Operation(summary = "更新模型")
    @PutMapping("/{id}")
    public CommonResult<AdminModelResp> update(@PathVariable Long id, @Valid @RequestBody AdminModelSaveReq req) {
        return CommonResult.success(adminModelService.update(id, req));
    }

    @Operation(summary = "启停模型")
    @PatchMapping("/{id}/enabled")
    public CommonResult<AdminModelResp> changeEnabled(
            @PathVariable Long id,
            @Valid @RequestBody AdminModelEnabledReq req) {
        return CommonResult.success(adminModelService.changeEnabled(id, req.getEnabled()));
    }

    @Operation(summary = "删除模型")
    @DeleteMapping("/{id}")
    public CommonResult<Void> delete(@PathVariable Long id) {
        adminModelService.delete(id);
        return CommonResult.success();
    }
}
