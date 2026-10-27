package cn.aigc.dit.server.controller;

import cn.aigc.dit.common.model.resp.CommonResult;
import cn.aigc.dit.server.model.req.WorkflowRunReq;
import cn.aigc.dit.server.model.req.WorkflowSaveReq;
import cn.aigc.dit.server.model.resp.WorkflowResp;
import cn.aigc.dit.server.model.resp.WorkflowRunResp;
import cn.aigc.dit.server.model.resp.WorkflowSnapshotResp;
import cn.aigc.dit.server.service.WorkflowRunService;
import cn.aigc.dit.server.service.WorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "高级工作台")
@RestController
@RequestMapping("/api/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;
    private final WorkflowRunService workflowRunService;

    @Operation(summary = "我的工作流与官方示例")
    @GetMapping
    public CommonResult<List<WorkflowResp>> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String q) {
        return CommonResult.success(workflowService.listMine(type, q));
    }

    @Operation(summary = "工作流详情，含画布")
    @GetMapping("/{id}")
    public CommonResult<WorkflowResp> get(@PathVariable Long id) {
        return CommonResult.success(workflowService.getMine(id));
    }

    @Operation(summary = "新建工作流")
    @PostMapping
    public CommonResult<WorkflowResp> create(@Valid @RequestBody WorkflowSaveReq req) {
        return CommonResult.success(workflowService.create(req));
    }

    @Operation(summary = "更新工作流画布")
    @PutMapping("/{id}")
    public CommonResult<WorkflowResp> update(@PathVariable Long id, @Valid @RequestBody WorkflowSaveReq req) {
        return CommonResult.success(workflowService.update(id, req));
    }

    @Operation(summary = "删除我保存的工作流")
    @DeleteMapping("/{id}")
    public CommonResult<Void> delete(@PathVariable Long id) {
        workflowService.delete(id);
        return CommonResult.success();
    }

    @Operation(summary = "按生成节点运行画布，结果写入我的创作")
    @PostMapping("/runs")
    public CommonResult<WorkflowRunResp> run(@Valid @RequestBody WorkflowRunReq req) {
        return CommonResult.success(workflowRunService.start(req));
    }

    @Operation(summary = "查询运行进度，并推进已完成节点的下游")
    @GetMapping("/runs/{id}")
    public CommonResult<WorkflowRunResp> run(@PathVariable Long id) {
        return CommonResult.success(workflowRunService.getMine(id));
    }

    @Operation(summary = "当前用户在该工作流的最近一次运行")
    @GetMapping("/{id}/runs/latest")
    public CommonResult<WorkflowRunResp> latestRun(@PathVariable Long id) {
        return CommonResult.success(workflowRunService.latestMine(id));
    }

    @Operation(summary = "当前保存画布的跨运行状态快照")
    @GetMapping("/{id}/runtime")
    public CommonResult<WorkflowSnapshotResp> snapshot(@PathVariable Long id) {
        return CommonResult.success(workflowRunService.snapshotMine(id));
    }
}
