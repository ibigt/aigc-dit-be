package cn.aigc.dit.server.controller;

import cn.aigc.dit.common.model.resp.CommonResult;
import cn.aigc.dit.server.config.AppVersion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "健康检查")
@RestController
@RequestMapping("/api")
public class HealthController {

    private final AppVersion appVersion;

    public HealthController(AppVersion appVersion) {
        this.appVersion = appVersion;
    }

    @Operation(summary = "存活探测")
    @GetMapping("/health")
    public CommonResult<Map<String, String>> health() {
        return CommonResult.success(Map.of("status", "UP", "service", "dit-server"));
    }

    @Operation(summary = "版本号")
    @GetMapping("/version")
    public CommonResult<Map<String, String>> version() {
        return CommonResult.success(Map.of("version", appVersion.getVersion()));
    }
}
