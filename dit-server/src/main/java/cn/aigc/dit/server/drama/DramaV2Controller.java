package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.server.drama.agent.DramaAnalysisResult;
import cn.aigc.dit.server.drama.entity.DramaAsset;
import cn.aigc.dit.server.drama.entity.DramaEpisode;
import cn.aigc.dit.server.drama.entity.DramaShot;
import cn.aigc.dit.server.drama.mapper.DramaAssetMapper;
import cn.aigc.dit.server.drama.mapper.DramaEpisodeMapper;
import cn.aigc.dit.server.drama.mapper.DramaShotMapper;
import cn.aigc.dit.server.drama.model.req.DramaScriptAnalyzeReq;
import cn.aigc.dit.server.drama.model.resp.DramaAssetResp;
import cn.aigc.dit.server.drama.model.resp.DramaEpisodeResp;
import cn.aigc.dit.server.drama.model.resp.DramaScriptAnalyzeV2Resp;
import cn.aigc.dit.server.drama.model.resp.DramaStoryboardResp;
import cn.aigc.dit.server.drama.text.ScriptDoc;
import cn.aigc.dit.server.drama.text.ScriptDocs;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v2 剧本分析 Controller。
 *
 * 暴露 2 个新端点：
 * - POST /api/drama/v2/episodes/{id}/script/analyze       全量跑 4 步
 * - POST /api/drama/v2/episodes/{id}/script/regenerate    重跑下游（assets + shots）
 *
 * 与 v1 /api/drama/episodes/{id}/script/analyze 并存，由前端按需选择。
 *
 * 设计要点：
 * - 不复用 DramaService.analyzeScript（避免与 v1 旧链产生隐式耦合）
 * - 走 DramaAnalysisFacade.analyzeV2 → DramaAnalysisOrchestrator
 * - 失败抛 BizException，前端可降级到 v1
 */
@Slf4j
@Tag(name = "v2 剧本分析")
@RestController
@RequestMapping("/api/drama/v2/episodes")
@RequiredArgsConstructor
public class DramaV2Controller {

    private final DramaAnalysisFacade facade;
    private final DramaEpisodeMapper episodeMapper;
    private final DramaAssetMapper assetMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DramaShotMapper shotMapper;
    private final DramaStoryboardService storyboardService;
    private final DramaService dramaService;

    @Operation(summary = "v2 全量分析：skeleton → script → assets → shots")
    @PostMapping("/{id}/script/analyze")
    public DramaScriptAnalyzeV2Resp analyzeV2(
            @PathVariable Long id,
            @Valid @RequestBody DramaScriptAnalyzeReq req) {
        Long userId = UserContext.requireUserId();
        DramaEpisode episode = requireEpisode(id, userId);

        DramaAnalysisResult result;
        try {
            result = facade.analyzeV2(episode, req.getSourceText(), null, null);
        } catch (BizException e) {
            // 失败：把失败上下文也返回给前端（方便诊断）
            log.warn("[drama-v2] analyze 失败 episodeId={} msg={}", id, e.getMessage());
            throw e;
        }

        compileQuietly(id);
        return toResp(id, result);
    }

    @Operation(summary = "v2 下游重跑：assets + shots（剧本已存在）")
    @PostMapping("/{id}/script/regenerate")
    public DramaScriptAnalyzeV2Resp regenerate(
            @PathVariable Long id,
            @Valid @RequestBody DramaScriptAnalyzeReq req) {
        Long userId = UserContext.requireUserId();
        DramaEpisode episode = requireEpisode(id, userId);

        if (episode.getScript() == null || episode.getScript().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "本集还没有解析出剧本，请先做一次完整分析");
        }
        ScriptDoc existing = ScriptDocs.fromMap(episode.getScript());
        if (existing == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "本集保存的剧本内容读不出来，无法重新生成资产和分镜");
        }

        DramaAnalysisResult result = facade.regenerateDownstream(episode, existing, null, null);
        compileQuietly(id);
        return toResp(id, result);
    }

    // ─────────────────────────────────────
    // 辅助
    // ─────────────────────────────────────

    /**
     * 分析完顺手编译一遍分镜。
     *
     * <p>v2 流水线只落库草稿，不调编译器，于是 visible_prompt / compiled_prompt 全是空的，
     * 页面的「视频提示词」只能靠 {@code displayVisiblePrompt} 临时渲染，也拿不到已编译的
     * 出片提示词。这里在分析成功后补一次编译，行为对齐 v1。
     *
     * <p>编译失败不回滚分析结果：分析已经落库了，前端还有「重新编译」按钮。
     */
    private void compileQuietly(Long episodeId) {
        try {
            storyboardService.compileEpisode(episodeId);
            log.info("[drama-v2] 分镜编译完成 episodeId={}", episodeId);
        } catch (RuntimeException e) {
            log.warn("[drama-v2] 分镜编译失败，不影响已落库的分析结果 episodeId={} msg={}",
                    episodeId, e.getMessage());
        }
    }

    private DramaEpisode requireEpisode(Long id, Long userId) {
        DramaEpisode ep = episodeMapper.selectById(id);
        if (ep == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "episode 不存在 id=" + id);
        }
        if (!userId.equals(ep.getUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权访问此 episode");
        }
        return ep;
    }

    private DramaScriptAnalyzeV2Resp toResp(Long episodeId, DramaAnalysisResult result) {
        DramaScriptAnalyzeV2Resp resp = new DramaScriptAnalyzeV2Resp();
        resp.setEngine(DramaAnalysisFacade.ENGINE_V2);

        // 失败的 step 名（驼峰 → 友好中文）
        if (result.failedAt() != null) {
            resp.setFailedAt(result.failedAt().name().toLowerCase());
        }

        // 步骤信息（snake_case 给前端易处理）
        if (result.stepErrors() != null && !result.stepErrors().isEmpty()) {
            Map<String, String> errs = new LinkedHashMap<>();
            result.stepErrors().forEach((k, v) -> errs.put(k.name().toLowerCase(), v));
            resp.setStepErrors(errs);
        }
        if (result.stepRetries() != null && !result.stepRetries().isEmpty()) {
            Map<String, Integer> rs = new LinkedHashMap<>();
            result.stepRetries().forEach((k, v) -> rs.put(k.name().toLowerCase(), v));
            resp.setStepRetries(rs);
        }
        if (result.stepSupervisorRepaired() != null && !result.stepSupervisorRepaired().isEmpty()) {
            Map<String, Boolean> reps = new LinkedHashMap<>();
            result.stepSupervisorRepaired().forEach((k, v) -> reps.put(k.name().toLowerCase(), v));
            resp.setStepSupervisorRepaired(reps);
        }

        resp.setPromptTokens(result.promptTokens());
        resp.setCompletionTokens(result.completionTokens());
        resp.setDurationMs(result.durationMs());
        resp.setWarnings(facade.buildWarnings(result));

        // v1 兼容字段
        try {
            resp.setEpisode(dramaService.getEpisode(episodeId));
        } catch (Exception e) {
            log.warn("[drama-v2] 装配 episodeResp 失败 episodeId={} msg={}", episodeId, e.getMessage());
        }
        try {
            List<DramaAsset> assets = assetMapper.selectList(
                    new LambdaQueryWrapper<DramaAsset>().eq(DramaAsset::getEpisodeId, episodeId));
            resp.setAssets(assets.stream().map(this::toAssetResp).toList());
        } catch (Exception e) {
            log.warn("[drama-v2] 装配 assets 失败 episodeId={} msg={}", episodeId, e.getMessage());
        }
        try {
            if (shotMapper != null) {
                resp.setStoryboard(storyboardService.getStoryboard(episodeId));
            }
        } catch (Exception e) {
            log.warn("[drama-v2] 装配 storyboard 失败 episodeId={} msg={}", episodeId, e.getMessage());
        }
        if (result.script() != null && result.script().getScenes() != null) {
            resp.setActualSceneCount(result.script().getScenes().size());
        }
        if (result.shots() != null && result.shots().getShots() != null) {
            resp.setActualShotCount(result.shots().getShots().size());
        }

        return resp;
    }

    private DramaAssetResp toAssetResp(DramaAsset a) {
        DramaAssetResp r = new DramaAssetResp();
        r.setId(a.getId());
        r.setSeriesId(a.getSeriesId());
        r.setEpisodeId(a.getEpisodeId());
        r.setName(a.getName());
        r.setType(a.getType());
        r.setStateTag(a.getStateTag());
        // 展示层再兜一层：模型偶尔把性别写成英文再自己改口（「35岁 male? no; 35岁，男，…」），
        // 页面「外形设定」不能露出这段垃圾（落库时已清过一次，这里防的是更早的脏数据）。
        r.setDescription(DramaAssetText.clean(a.getDescription()));
        r.setIdentityInvariants(DramaAssetText.clean(a.getIdentityInvariants()));
        r.setImagePrompt(DramaAssetText.clean(a.getImagePrompt()));
        r.setVoiceLock(DramaAssetText.clean(a.getVoiceLock()));
        r.setMustSplit(a.getMustSplit());
        r.setStatus(a.getStatus());
        return r;
    }
}
