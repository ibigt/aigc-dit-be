package cn.aigc.dit.server.controller;

import cn.aigc.dit.common.model.resp.CommonResult;
import cn.aigc.dit.common.model.resp.PageQueryResp;
import cn.aigc.dit.server.model.req.AdminUserCreateReq;
import cn.aigc.dit.server.model.req.AdminUserStatusReq;
import cn.aigc.dit.server.model.req.AdminUserUpdateReq;
import cn.aigc.dit.server.model.resp.AdminUserResp;
import cn.aigc.dit.server.model.resp.ResetPasswordResp;
import cn.aigc.dit.server.service.AdminUserService;
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

@Tag(name = "管理端-用户")
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final AdminUserService adminUserService;

    @Operation(summary = "用户分页")
    @GetMapping
    public CommonResult<PageQueryResp<AdminUserResp>> page(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status) {
        int safePage = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int safeSize = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
        return CommonResult.success(adminUserService.pageUsers(safePage, safeSize, keyword, role, status));
    }

    @Operation(summary = "创建用户")
    @PostMapping
    public CommonResult<AdminUserResp> create(@Valid @RequestBody AdminUserCreateReq req) {
        return CommonResult.success(adminUserService.create(req));
    }

    @Operation(summary = "更新用户")
    @PutMapping("/{id}")
    public CommonResult<AdminUserResp> update(@PathVariable Long id, @Valid @RequestBody AdminUserUpdateReq req) {
        return CommonResult.success(adminUserService.update(id, req));
    }

    @Operation(summary = "启停用户")
    @PatchMapping("/{id}/status")
    public CommonResult<AdminUserResp> changeStatus(@PathVariable Long id, @Valid @RequestBody AdminUserStatusReq req) {
        return CommonResult.success(adminUserService.changeStatus(id, req.getStatus()));
    }

    @Operation(summary = "重置密码")
    @PostMapping("/{id}/reset-password")
    public CommonResult<ResetPasswordResp> resetPassword(@PathVariable Long id) {
        return CommonResult.success(adminUserService.resetPassword(id));
    }

    @Operation(summary = "删除用户")
    @DeleteMapping("/{id}")
    public CommonResult<Void> delete(@PathVariable Long id) {
        adminUserService.delete(id);
        return CommonResult.success();
    }
}
