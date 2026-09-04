package cn.aigc.dit.server.controller;

import cn.aigc.dit.common.model.resp.CommonResult;
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

    @Operation(summary = "存活探测")
    @GetMapping("/health")
    public CommonResult<Map<String, String>> health() {
        return CommonResult.success(Map.of("status", "UP", "service", "dit-server"));
    }
}
