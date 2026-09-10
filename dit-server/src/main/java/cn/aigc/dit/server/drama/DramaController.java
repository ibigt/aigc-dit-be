package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.model.resp.CommonResult;
import cn.aigc.dit.server.drama.model.req.DramaEpisodeUpdateReq;
import cn.aigc.dit.server.drama.model.req.DramaSeriesCreateReq;
import cn.aigc.dit.server.drama.model.req.DramaSeriesUpdateReq;
import cn.aigc.dit.server.drama.model.resp.DramaEpisodeResp;
import cn.aigc.dit.server.drama.model.resp.DramaSeriesResp;
import cn.aigc.dit.server.drama.model.resp.DramaStyleResp;
import cn.aigc.dit.server.drama.model.resp.DramaTextModelResp;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "漫剧")
@RestController
@RequestMapping("/api/drama")
@RequiredArgsConstructor
public class DramaController {

    private final DramaService dramaService;

    @Operation(summary = "可选视觉风格（图例；不含编译前缀）")
    @GetMapping("/styles")
    public CommonResult<List<DramaStyleResp>> listStyles() {
        return CommonResult.success(dramaService.listStyles());
    }

    @Operation(summary = "已启用文本模型（新建/更换绑定；不含密钥）")
    @GetMapping("/text-models")
    public CommonResult<List<DramaTextModelResp>> listTextModels() {
        return CommonResult.success(dramaService.listEnabledTextModels());
    }

    @Operation(summary = "系列列表")
    @GetMapping("/series")
    public CommonResult<List<DramaSeriesResp>> listSeries() {
        return CommonResult.success(dramaService.listSeries());
    }

    @Operation(summary = "系列详情（含剧集）")
    @GetMapping("/series/{id}")
    public CommonResult<DramaSeriesResp> getSeries(@PathVariable Long id) {
        return CommonResult.success(dramaService.getSeries(id));
    }

    @Operation(summary = "新建系列并创建第 1 集")
    @PostMapping("/series")
    public CommonResult<DramaSeriesResp> createSeries(@Valid @RequestBody DramaSeriesCreateReq req) {
        return CommonResult.success(dramaService.createSeries(req));
    }

    @Operation(summary = "更新系列基础信息")
    @PutMapping("/series/{id}")
    public CommonResult<DramaSeriesResp> updateSeries(
            @PathVariable Long id,
            @Valid @RequestBody DramaSeriesUpdateReq req) {
        return CommonResult.success(dramaService.updateSeries(id, req));
    }

    @Operation(summary = "剧集详情")
    @GetMapping("/episodes/{id}")
    public CommonResult<DramaEpisodeResp> getEpisode(@PathVariable Long id) {
        return CommonResult.success(dramaService.getEpisode(id));
    }

    @Operation(summary = "更新剧集名称")
    @PutMapping("/episodes/{id}")
    public CommonResult<DramaEpisodeResp> updateEpisode(
            @PathVariable Long id,
            @Valid @RequestBody DramaEpisodeUpdateReq req) {
        return CommonResult.success(dramaService.updateEpisode(id, req));
    }
}
