package cn.aigc.dit.server.controller;

import cn.aigc.dit.common.model.resp.CommonResult;
import cn.aigc.dit.server.model.req.CanvasAssetSaveReq;
import cn.aigc.dit.server.model.resp.CanvasAssetResp;
import cn.aigc.dit.server.service.CanvasAssetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "画布资产")
@RestController
@RequestMapping("/api/canvas-assets")
@RequiredArgsConstructor
public class CanvasAssetController {

    private final CanvasAssetService canvasAssetService;

    @Operation(summary = "我的画布资产列表（不含节点图）")
    @GetMapping
    public CommonResult<List<CanvasAssetResp>> list() {
        return CommonResult.success(canvasAssetService.listMine());
    }

    @Operation(summary = "画布资产详情，含节点图")
    @GetMapping("/{id}")
    public CommonResult<CanvasAssetResp> get(@PathVariable Long id) {
        return CommonResult.success(canvasAssetService.getMine(id));
    }

    @Operation(summary = "把选中的节点保存为画布资产")
    @PostMapping
    public CommonResult<CanvasAssetResp> create(@Valid @RequestBody CanvasAssetSaveReq req) {
        return CommonResult.success(canvasAssetService.create(req));
    }

    @Operation(summary = "删除我的画布资产")
    @DeleteMapping("/{id}")
    public CommonResult<Void> delete(@PathVariable Long id) {
        canvasAssetService.deleteMine(id);
        return CommonResult.success();
    }
}
