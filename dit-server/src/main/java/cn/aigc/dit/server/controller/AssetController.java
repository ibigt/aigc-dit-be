package cn.aigc.dit.server.controller;

import cn.aigc.dit.common.model.resp.CommonResult;
import cn.aigc.dit.server.model.resp.AssetResp;
import cn.aigc.dit.server.service.AssetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "素材库")
@RestController
@RequestMapping("/api/assets")
@RequiredArgsConstructor
public class AssetController {

    private final AssetService assetService;

    @Operation(summary = "我的素材（@ 选择器与素材库共用）")
    @GetMapping
    public CommonResult<List<AssetResp>> list(
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String orderBy) {
        return CommonResult.success(assetService.listMine(kind, q, orderBy));
    }

    @Operation(summary = "上传图片、视频或音频（文件存入 MinIO）")
    @PostMapping
    public CommonResult<AssetResp> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String name) {
        return CommonResult.success(assetService.upload(file, name));
    }

    @Operation(summary = "删除素材")
    @DeleteMapping("/{id}")
    public CommonResult<Void> delete(@PathVariable Long id) {
        assetService.deleteMine(id);
        return CommonResult.success();
    }
}
