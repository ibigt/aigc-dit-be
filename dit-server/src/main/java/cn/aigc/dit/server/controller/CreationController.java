package cn.aigc.dit.server.controller;

import cn.aigc.dit.common.model.resp.CommonResult;
import cn.aigc.dit.server.model.req.CreationSessionCreateReq;
import cn.aigc.dit.server.model.req.CreationSessionRenameReq;
import cn.aigc.dit.server.model.resp.AssetResp;
import cn.aigc.dit.server.model.resp.CreationItemResp;
import cn.aigc.dit.server.model.resp.CreationProjectResp;
import cn.aigc.dit.server.model.resp.CreationSessionResp;
import cn.aigc.dit.server.service.CreationService;
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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

@Tag(name = "创作")
@RestController
@RequiredArgsConstructor
public class CreationController {

    private final CreationService creationService;

    @Operation(summary = "会话列表")
    @GetMapping("/api/sessions")
    public CommonResult<List<CreationSessionResp>> sessions() {
        return CommonResult.success(creationService.listSessions());
    }

    @Operation(summary = "会话详情（含任务）")
    @GetMapping("/api/sessions/{id}")
    public CommonResult<CreationSessionResp> session(@PathVariable Long id) {
        return CommonResult.success(creationService.getSession(id));
    }

    @Operation(summary = "新建会话")
    @PostMapping("/api/sessions")
    public CommonResult<CreationSessionResp> createSession(@Valid @RequestBody CreationSessionCreateReq req) {
        return CommonResult.success(creationService.createSession(req));
    }

    @Operation(summary = "重命名会话")
    @PutMapping("/api/sessions/{id}")
    public CommonResult<CreationSessionResp> renameSession(
            @PathVariable Long id, @Valid @RequestBody CreationSessionRenameReq req) {
        return CommonResult.success(creationService.renameSession(id, req));
    }

    @Operation(summary = "删除会话（作品仍保留在我的创作）")
    @DeleteMapping("/api/sessions/{id}")
    public CommonResult<Void> deleteSession(@PathVariable Long id) {
        creationService.deleteSession(id);
        return CommonResult.success();
    }

    @Operation(summary = "我的创作所属项目")
    @GetMapping("/api/creations/projects")
    public CommonResult<List<CreationProjectResp>> projects() {
        return CommonResult.success(creationService.listProjects());
    }

    @Operation(summary = "我的创作")
    @GetMapping("/api/creations")
    public CommonResult<List<CreationItemResp>> creations(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String project,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return CommonResult.success(creationService.listMine(category, q, project, from, to));
    }

    @Operation(summary = "作品详情")
    @GetMapping("/api/creations/{id}")
    public CommonResult<CreationItemResp> creation(@PathVariable Long id) {
        return CommonResult.success(creationService.getMine(id));
    }

    @Operation(summary = "保存作品到素材库")
    @PostMapping("/api/creations/{id}/to-asset")
    public CommonResult<AssetResp> toAsset(@PathVariable Long id) {
        return CommonResult.success(creationService.saveToAsset(id));
    }

    @Operation(summary = "删除作品")
    @DeleteMapping("/api/creations/{id}")
    public CommonResult<Void> delete(@PathVariable Long id) {
        creationService.deleteMine(id);
        return CommonResult.success();
    }
}
