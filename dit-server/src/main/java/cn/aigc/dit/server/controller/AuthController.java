package cn.aigc.dit.server.controller;

import cn.aigc.dit.common.model.resp.CommonResult;
import cn.aigc.dit.server.model.req.LoginReq;
import cn.aigc.dit.server.model.req.RegisterReq;
import cn.aigc.dit.server.model.req.UpdateProfileReq;
import cn.aigc.dit.server.model.resp.TokenResp;
import cn.aigc.dit.server.model.resp.UserProfileResp;
import cn.aigc.dit.server.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "认证")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "登录")
    @PostMapping("/login")
    public CommonResult<TokenResp> login(@Valid @RequestBody LoginReq req) {
        return CommonResult.success(authService.login(req));
    }

    @Operation(summary = "注册")
    @PostMapping("/register")
    public CommonResult<TokenResp> register(@Valid @RequestBody RegisterReq req) {
        return CommonResult.success(authService.register(req));
    }

    @Operation(summary = "当前用户")
    @GetMapping("/me")
    public CommonResult<UserProfileResp> me() {
        return CommonResult.success(authService.currentUser());
    }

    @Operation(summary = "更新个人信息")
    @PatchMapping("/profile")
    public CommonResult<UserProfileResp> updateProfile(@Valid @RequestBody UpdateProfileReq req) {
        return CommonResult.success(authService.updateProfile(req));
    }
}
