package cn.aigc.dit.server.controller;

import cn.aigc.dit.common.model.resp.CommonResult;
import cn.aigc.dit.server.drama.DramaContract;
import cn.aigc.dit.server.drama.text.AssetDraftList;
import cn.aigc.dit.server.drama.text.DramaTextService;
import cn.aigc.dit.server.drama.text.ScriptDoc;
import cn.aigc.dit.server.drama.text.ScriptDocs;
import cn.aigc.dit.server.drama.text.ShotDraftList;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 剧本理解层自测接口：只跑解析，不写任何表。
 * <p>
 * 《乙工作内容》写的是 {@code POST /api/drama/text/preview}，这里落在 {@code /api/admin/**} 下，
 * 与 {@link AdminModelController} 同构——该前缀在 SecurityConfig 里已按 ADMIN 收口。
 */
@Tag(name = "漫剧剧本理解层（管理端自测）")
@RestController
@RequestMapping("/api/admin/drama/text")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminDramaTextController {

    private final ObjectProvider<DramaTextService> textService;

    @Data
    public static class PreviewReq {
        private Long seriesId;
        private String raw;
        private List<String> lockedAssetNames;
    }

    @Operation(summary = "预览分场/资产/分镜草稿；不入库")
    @PostMapping("/preview")
    public CommonResult<Map<String, Object>> preview(@RequestBody PreviewReq req) {
        DramaTextService svc = textService.getIfAvailable();
        Map<String, Object> body = new LinkedHashMap<>();
        if (svc == null) {
            body.put("engine", DramaContract.ENGINE_PENDING);
            body.put("message", "剧本理解层未合入");
            return CommonResult.success(body);
        }
        long seriesId = req.getSeriesId() == null ? 0L : req.getSeriesId();
        ScriptDoc doc = req.getLockedAssetNames() == null || req.getLockedAssetNames().isEmpty()
                ? svc.parseScript(seriesId, req.getRaw())
                : svc.parseNextEpisode(seriesId, req.getRaw(), req.getLockedAssetNames());
        AssetDraftList assets = svc.extractAssets(seriesId, doc);
        List<String> assetNames = assets.getAssets().stream().map(AssetDraftList.AssetDraft::getName).toList();
        ShotDraftList shots = svc.draftShots(seriesId, doc, assetNames);

        body.put("engine", svc.engine(seriesId));
        body.put("script", ScriptDocs.toMap(doc));
        body.put("assets", assets);
        body.put("shots", shots);
        return CommonResult.success(body);
    }
}
