package cn.aigc.dit.server.controller;

import cn.aigc.dit.common.model.resp.CommonResult;
import cn.aigc.dit.server.model.req.GenerateSubmitReq;
import cn.aigc.dit.server.model.resp.GenerateTaskResp;
import cn.aigc.dit.server.service.GenerateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "生成")
@RestController
@RequestMapping("/api/generate")
@RequiredArgsConstructor
public class GenerateController {

    private final GenerateService generateService;

    @Operation(summary = "提交生成（Canonical GenerateRequest）")
    @PostMapping
    public CommonResult<GenerateTaskResp> submit(@Valid @RequestBody GenerateSubmitReq req) {
        return CommonResult.success(generateService.submit(req));
    }

    @Operation(summary = "查询生成任务")
    @GetMapping("/tasks/{id}")
    public CommonResult<GenerateTaskResp> get(@PathVariable Long id) {
        return CommonResult.success(generateService.getMine(id));
    }

    @Operation(summary = "取消等待中或运行中的生成任务，未提交供应商的退还积分")
    @PostMapping("/tasks/{id}/cancel")
    public CommonResult<Void> cancel(@PathVariable Long id) {
        generateService.cancelOwned(id);
        return CommonResult.success();
    }
}
