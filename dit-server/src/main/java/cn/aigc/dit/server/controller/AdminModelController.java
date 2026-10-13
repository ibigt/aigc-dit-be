package cn.aigc.dit.server.controller;

import cn.aigc.dit.common.model.resp.CommonResult;
import cn.aigc.dit.server.model.req.AdminModelEnabledReq;
import cn.aigc.dit.server.model.req.AdminModelSaveReq;
import cn.aigc.dit.server.model.req.AdminModelTestConnectionReq;
import cn.aigc.dit.server.model.req.ArkReviewCredentialSaveReq;
import cn.aigc.dit.server.model.resp.AdminModelResp;
import cn.aigc.dit.server.model.resp.AdminModelTestConnectionResp;
import cn.aigc.dit.server.model.resp.ArkReviewCredentialResp;
import cn.aigc.dit.server.service.AdminModelService;
import cn.aigc.dit.server.service.ArkReviewCredentialService;
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
    private final ArkReviewCredentialService arkReviewCredentialService;

    @Operation(summary = "角色审核密钥是否已配置，不回传明文")
    @GetMapping("/review-credential")
    public CommonResult<ArkReviewCredentialResp> reviewCredential() {
        return CommonResult.success(arkReviewCredentialService.status());
    }

    @Operation(summary = "保存角色审核密钥，加密落库；留空字段保持原值")
    @PutMapping("/review-credential")
    public CommonResult<ArkReviewCredentialResp> saveReviewCredential(@RequestBody ArkReviewCredentialSaveReq req) {
        String accessKey = req == null ? null : req.getAccessKey();
        String secretKey = req == null ? null : req.getSecretKey();
        return CommonResult.success(arkReviewCredentialService.save(accessKey, secretKey));
    }

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

    @Operation(summary = "测试模型连接（按模型类型与适配协议探活，不写库、不消耗 token/字符额度）")
    @PostMapping("/test-connection")
    public CommonResult<AdminModelTestConnectionResp> testConnection(
            @RequestBody AdminModelTestConnectionReq req) {
        return CommonResult.success(adminModelService.testConnection(req));
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
