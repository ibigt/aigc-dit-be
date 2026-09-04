package cn.aigc.dit.server.controller;

import cn.aigc.dit.common.model.resp.CommonResult;
import cn.aigc.dit.server.model.resp.ModelPublicResp;
import cn.aigc.dit.server.service.AdminModelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "模型")
@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
public class ModelController {

    private final AdminModelService adminModelService;

    @Operation(summary = "创作端可用模型")
    @GetMapping
    public CommonResult<List<ModelPublicResp>> listEnabled() {
        return CommonResult.success(adminModelService.listEnabledPublic());
    }
}
