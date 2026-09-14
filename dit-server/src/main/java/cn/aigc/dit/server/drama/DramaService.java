package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.generate.GenerateMode;
import cn.aigc.dit.server.drama.entity.DramaAsset;
import cn.aigc.dit.server.drama.entity.DramaEpisode;
import cn.aigc.dit.server.drama.entity.DramaSeries;
import cn.aigc.dit.server.drama.mapper.DramaAssetMapper;
import cn.aigc.dit.server.drama.mapper.DramaEpisodeMapper;
import cn.aigc.dit.server.drama.mapper.DramaSeriesMapper;
import cn.aigc.dit.server.drama.model.req.DramaImageModelBindReq;
import cn.aigc.dit.server.drama.model.req.DramaVideoModelBindReq;
import cn.aigc.dit.server.generate.GenerateRequests;
import cn.aigc.dit.server.drama.model.req.DramaEpisodeUpdateReq;
import cn.aigc.dit.server.drama.model.req.DramaScriptImportReq;
import cn.aigc.dit.server.drama.model.req.DramaScriptAnalyzeReq;
import cn.aigc.dit.server.drama.model.req.DramaScriptSaveReq;
import cn.aigc.dit.server.drama.model.req.DramaSeriesCreateReq;
import cn.aigc.dit.server.drama.model.req.DramaSeriesUpdateReq;
import cn.aigc.dit.server.drama.model.req.DramaTextModelBindReq;
import cn.aigc.dit.server.drama.model.resp.DramaEpisodeResp;
import cn.aigc.dit.server.drama.model.resp.DramaSeriesResp;
import cn.aigc.dit.server.drama.model.resp.DramaImageModelResp;
import cn.aigc.dit.server.drama.model.resp.DramaStyleResp;
import cn.aigc.dit.server.drama.model.resp.DramaTextModelResp;
import cn.aigc.dit.server.drama.model.resp.DramaVideoModelResp;
import cn.aigc.dit.server.drama.model.resp.DramaScriptAnalyzeResp;
import cn.aigc.dit.server.drama.model.resp.DramaStoryboardResp;
import cn.aigc.dit.server.drama.model.resp.DramaAssetResp;
import cn.aigc.dit.server.drama.text.DramaTextService;
import cn.aigc.dit.server.drama.text.ScriptDoc;
import cn.aigc.dit.server.drama.text.ScriptDocs;
import cn.aigc.dit.server.drama.text.TextChatGateway;
import cn.aigc.dit.server.drama.text.TextChatGatewayImpl;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.mapper.AiModelMapper;
import cn.aigc.dit.server.media.MediaUrls;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DramaService {

    private final DramaSeriesMapper seriesMapper;
    private final DramaEpisodeMapper episodeMapper;
    private final DramaAssetMapper assetMapper;
    private final AiModelMapper aiModelMapper;
    private final ObjectProvider<DramaTextService> textService;
    private final TextChatGateway textChatGateway;
    private final DramaStoryboardService storyboardService;
    private final DramaFilmService filmService;
    @Autowired
    private DramaAssetService assetService;
    @Autowired
    private DramaScriptAnalyzeLock analyzeLock;
    /** 走 Spring 代理，让 import/lock 各自提交。 */
    @Autowired
    private ObjectProvider<DramaService> self;

    public List<DramaSeriesResp> listSeries() {
        Long userId = UserContext.requireUserId();
        List<DramaSeries> series = seriesMapper.selectList(new LambdaQueryWrapper<DramaSeries>()
                .eq(DramaSeries::getUserId, userId)
                .orderByDesc(DramaSeries::getUpdatedAt));
        if (series.isEmpty()) {
            return List.of();
        }
        List<Long> seriesIds = series.stream().map(DramaSeries::getId).toList();
        Map<Long, List<DramaEpisode>> bySeries = episodeMapper.selectList(new LambdaQueryWrapper<DramaEpisode>()
                        .in(DramaEpisode::getSeriesId, seriesIds)
                        .orderByAsc(DramaEpisode::getEpisodeNumber))
                .stream()
                .collect(Collectors.groupingBy(DramaEpisode::getSeriesId));
        Map<Long, String> modelNames = modelNames(series.stream()
                .flatMap(item -> java.util.stream.Stream.of(
                        item.getTextModelId(), item.getImageModelId(), item.getVideoModelId()))
                .toList());
        Map<Long, String> sceneCovers = sceneCoverKeys(seriesIds);
        return series.stream()
                .map(item -> toSeriesResp(item, bySeries.getOrDefault(item.getId(), List.of()), false, modelNames, sceneCovers))
                .toList();
    }

    public DramaSeriesResp getSeries(Long id) {
        DramaSeries series = requireSeries(id);
        List<DramaEpisode> episodes = episodeMapper.selectList(new LambdaQueryWrapper<DramaEpisode>()
                .eq(DramaEpisode::getSeriesId, series.getId())
                .orderByAsc(DramaEpisode::getEpisodeNumber));
        return toSeriesResp(series, episodes, true, modelNames(idsOf(
                series.getTextModelId(), series.getImageModelId(), series.getVideoModelId())),
                sceneCoverKeys(List.of(series.getId())));
    }

    @Transactional
    public DramaSeriesResp createSeries(DramaSeriesCreateReq req) {
        Long userId = UserContext.requireUserId();
        DramaSeries series = new DramaSeries();
        series.setUserId(userId);
        series.setName(req.getName().trim());
        applyStyle(series, req.getStyleKey());
        series.setAspectRatio(normalizeAspectRatio(req.getAspectRatio()));
        series.setDefaultResolution(normalizeResolution(req.getDefaultResolution()));
        series.setDefaultDurationSec(normalizeDuration(req.getDefaultDurationSec()));
        series.setPlannedEpisodeCount(normalizePlanned(req.getPlannedEpisodeCount()));
        series.setTextModelId(resolveCreateTextModelId(req.getTextModelId()));
        seriesMapper.insert(series);

        DramaEpisode episode = new DramaEpisode();
        episode.setSeriesId(series.getId());
        episode.setUserId(userId);
        episode.setEpisodeNumber(1);
        episode.setName(StringUtils.hasText(req.getFirstEpisodeName())
                ? req.getFirstEpisodeName().trim()
                : "第 1 集");
        episode.setStatus(DramaContract.STATUS_NOT_STARTED);
        episode.setEngine(DramaContract.ENGINE_PENDING);
        episodeMapper.insert(episode);
        return toSeriesResp(series, List.of(episode), true, modelNames(idsOf(series.getTextModelId())), Map.of());
    }

    @Transactional
    public DramaSeriesResp updateSeries(Long id, DramaSeriesUpdateReq req) {
        DramaSeries series = requireSeries(id);
        String previousStyle = series.getStyleKey();
        String previousPrefix = series.getStylePrefix();
        String previousRatio = series.getAspectRatio();
        if (StringUtils.hasText(req.getName())) {
            series.setName(req.getName().trim());
        }
        if (req.getStyleKey() != null) {
            applyStyle(series, req.getStyleKey());
        }
        if (req.getAspectRatio() != null) {
            series.setAspectRatio(normalizeAspectRatio(req.getAspectRatio()));
        }
        if (req.getDefaultResolution() != null) {
            series.setDefaultResolution(normalizeResolution(req.getDefaultResolution()));
        }
        if (req.getDefaultDurationSec() != null) {
            series.setDefaultDurationSec(normalizeDuration(req.getDefaultDurationSec()));
        }
        if (req.getPlannedEpisodeCount() != null) {
            series.setPlannedEpisodeCount(normalizePlanned(req.getPlannedEpisodeCount()));
        }
        if (req.getTextModelId() != null) {
            series.setTextModelId(requireEnabledTextModel(req.getTextModelId()).getId());
        }
        seriesMapper.updateById(series);
        boolean styleChanged = req.getStyleKey() != null && !java.util.Objects.equals(previousStyle, series.getStyleKey());
        boolean ratioChanged = req.getAspectRatio() != null && !java.util.Objects.equals(previousRatio, series.getAspectRatio());
        if (styleChanged) {
            rewriteUnlockedAssetStyle(series.getId(), previousPrefix, series.getStylePrefix());
        }
        if (styleChanged || ratioChanged) {
            storyboardService.expireCompiledBySeries(series.getId());
        }
        return getSeries(series.getId());
    }

    @Transactional
    public void deleteSeries(Long id) {
        DramaSeries series = requireSeries(id);
        seriesMapper.deleteById(series.getId());
    }

    public DramaEpisodeResp getEpisode(Long id) {
        return toEpisodeResp(requireEpisode(id), true);
    }

    @Transactional
    public DramaEpisodeResp updateEpisode(Long id, DramaEpisodeUpdateReq req) {
        DramaEpisode episode = requireEpisode(id);
        episode.setName(req.getName().trim());
        episodeMapper.updateById(episode);
        return toEpisodeResp(episode, true);
    }

    /**
     * 导入原文。乙合入后写入 {@code script}；否则只存 {@code source_text}，engine=pending。
     */
    @Transactional
    public DramaEpisodeResp importSource(Long id, DramaScriptImportReq req) {
        DramaEpisode episode = requireEpisode(id);
        String raw = req.getSourceText().trim();
        onScriptMutated(episode);
        episode.setSourceText(raw);
        applyParseIfPresent(episode, raw);
        episode.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
        episodeMapper.updateById(episode);
        touchSeries(episode.getSeriesId());
        return toEpisodeResp(episode, true);
    }

    /**
     * 从梗概生成分场剧本。需乙实现 + 项目绑定的文本模型可用。
     */
    @Transactional
    public DramaEpisodeResp generateScript(Long id, DramaScriptImportReq req) {
        DramaEpisode episode = requireEpisode(id);
        DramaTextService svc = requireTextService();
        if (!textChatGateway.llmAvailable(episode.getSeriesId())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前项目未绑定可用文本模型，无法生成剧本");
        }
        String raw = req.getSourceText().trim();
        onScriptMutated(episode);
        episode.setSourceText(raw);
        ScriptDoc doc = svc.parseScript(episode.getSeriesId(), raw);
        episode.setScript(ScriptDocs.toMap(doc));
        episode.setEngine(DramaContract.normalizeEngine(svc.engine(episode.getSeriesId())));
        episode.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
        episodeMapper.updateById(episode);
        touchSeries(episode.getSeriesId());
        return toEpisodeResp(episode, true);
    }

    /**
     * 剧本页的一键分析闭环。各阶段仍复用现有服务，便于单独重跑和人工编辑。
     * 不能包在同一个事务里：import 会先锁住剧集行，随后 draft 要用 REQUIRES_NEW 写拆镜锁，
     * 会把后一次更新堵在同一行上。Java 线程再等 claim 返回，刷新看不到未提交分场，删除也会一直卡住。
     */
    public DramaScriptAnalyzeResp analyzeScript(Long id, DramaScriptAnalyzeReq req) {
        requireEpisode(id);
        String raw = req.getSourceText().trim();
        DramaScriptImportReq importReq = new DramaScriptImportReq();
        importReq.setSourceText(raw);

        DramaService ops = scriptOps();
        if (analyzeLock != null) {
            analyzeLock.claim(id);
        }
        try {
            // import 会优先使用已配置的文本模型，失败时由文本服务回落到规则分场。
            ops.importSource(id, importReq);
            ops.lockScript(id);
            // 一键分析只同步完成分场和规则抽资产；LLM 拆镜后台跑，避免剧本页被第三次模型调用拖死。
            List<DramaAssetResp> assets = assetService.extractFromScript(id, false);
            int episodeBudget = req.getEpisodeDurationSec() == null ? 15 : req.getEpisodeDurationSec();
            int targetShots = req.getShotsPerEpisode() == null ? 8 : req.getShotsPerEpisode();
            storyboardService.enqueueAutoDraft(id, episodeBudget, targetShots);
            DramaStoryboardResp storyboard = storyboardService.getStoryboard(id);

            DramaScriptAnalyzeResp result = new DramaScriptAnalyzeResp();
            result.setEpisode(getEpisode(id));
            result.setAssets(assets);
            result.setStoryboard(storyboard);
            result.setRequestedEpisodeCount(req.getEpisodeCount() == null ? 1 : req.getEpisodeCount());
            result.setRequestedDurationSec(episodeBudget);
            result.setRequestedShots(targetShots);
            result.setEngine(result.getEpisode().getEngine());
            result.getWarnings().add("分镜正在后台按剧本自动拆分，打开「分镜」页可查看进度。");
            ScriptDoc analyzedDoc = result.getEpisode().getScript() == null
                    ? null : ScriptDocs.fromMap(result.getEpisode().getScript());
            int sceneCount = analyzedDoc == null || analyzedDoc.getScenes() == null
                    ? 0 : analyzedDoc.getScenes().size();
            result.setActualSceneCount(sceneCount);
            result.setActualShotCount(0);
            if (result.getRequestedEpisodeCount() > 1) {
                result.getWarnings().add("当前接口先完成第 1 集分析，多集拆分将在剧集编排阶段接入。");
            }
            if (StringUtils.hasText(req.getRequirements())) {
                result.getWarnings().add("补充要求已记录在本次请求中；当前解析器暂不覆盖已有结构化字段。");
            }
            return result;
        } finally {
            if (analyzeLock != null) {
                analyzeLock.release(id);
            }
        }
    }

    private DramaService scriptOps() {
        DramaService bean = self == null ? null : self.getIfAvailable();
        return bean != null ? bean : this;
    }

    /**
     * 人工保存分场剧本。已锁定后再改：本集回到待确认，下游标已过期。
     */
    @Transactional
    public DramaEpisodeResp saveScript(Long id, DramaScriptSaveReq req) {
        DramaEpisode episode = requireEpisode(id);
        if (req.getSourceText() == null && req.getScript() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请填写剧本原文或分场内容");
        }
        onScriptMutated(episode);
        if (req.getSourceText() != null) {
            episode.setSourceText(StringUtils.hasText(req.getSourceText()) ? req.getSourceText().trim() : null);
        }
        if (req.getScript() != null) {
            episode.setScript(ScriptDocs.toMap(req.getScript()));
        }
        if (DramaContract.STATUS_NOT_STARTED.equals(episode.getStatus())) {
            episode.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
        }
        episodeMapper.updateById(episode);
        touchSeries(episode.getSeriesId());
        return toEpisodeResp(episode, true);
    }

    /**
     * 锁定分场剧本。乙未合入时有原文即可锁，供甲当纯文本编辑。
     */
    @Transactional
    public DramaEpisodeResp lockScript(Long id) {
        DramaEpisode episode = requireEpisode(id);
        if (DramaContract.STATUS_LOCKED.equals(episode.getStatus())) {
            return toEpisodeResp(episode, true);
        }
        if (!ScriptDocs.lockable(episode.getSourceText(), episode.getScript())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先导入或生成剧本");
        }
        episode.setStatus(DramaContract.STATUS_LOCKED);
        episodeMapper.updateById(episode);
        touchSeries(episode.getSeriesId());
        return toEpisodeResp(episode, true);
    }

    private DramaSeries requireSeries(Long id) {
        DramaSeries series = seriesMapper.selectById(id);
        if (series == null || !series.getUserId().equals(UserContext.requireUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "漫剧项目不存在");
        }
        return series;
    }

    private DramaEpisode requireEpisode(Long id) {
        DramaEpisode episode = episodeMapper.selectById(id);
        if (episode == null || !episode.getUserId().equals(UserContext.requireUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "剧集不存在");
        }
        return episode;
    }

    private DramaSeriesResp toSeriesResp(DramaSeries series, List<DramaEpisode> episodes, boolean withEpisodes,
                                         Map<Long, String> modelNames, Map<Long, String> sceneCovers) {
        List<DramaEpisode> ordered = episodes.stream()
                .sorted(Comparator.comparing(DramaEpisode::getEpisodeNumber))
                .toList();
        DramaSeriesResp resp = new DramaSeriesResp();
        resp.setId(series.getId());
        resp.setName(series.getName());
        DramaStyles.Spec style = DramaStyles.require(
                StringUtils.hasText(series.getStyleKey()) ? series.getStyleKey() : DramaStyles.ANIME_COMIC);
        resp.setStyleKey(style.key());
        resp.setStyleName(style.name());
        resp.setStyleCover(style.cover());
        String coverKey = sceneCovers == null ? null : sceneCovers.get(series.getId());
        resp.setCoverKey(coverKey);
        resp.setCoverUrl(MediaUrls.of(coverKey));
        resp.setAspectRatio(series.getAspectRatio());
        resp.setDefaultResolution(series.getDefaultResolution());
        resp.setDefaultDurationSec(series.getDefaultDurationSec());
        resp.setPlannedEpisodeCount(series.getPlannedEpisodeCount());
        resp.setTextModelId(series.getTextModelId());
        if (series.getTextModelId() != null) {
            resp.setTextModelName(modelNames.get(series.getTextModelId()));
        }
        resp.setTextModelUsable(textChatGateway.llmAvailable(series.getId()));
        resp.setImageModelId(series.getImageModelId());
        resp.setImageAdapterKey(series.getImageAdapterKey());
        if (series.getImageModelId() != null) {
            resp.setImageModelName(modelNames.get(series.getImageModelId()));
        }
        resp.setVideoModelId(series.getVideoModelId());
        resp.setVideoAdapterKey(series.getVideoAdapterKey());
        if (series.getVideoModelId() != null) {
            resp.setVideoModelName(modelNames.get(series.getVideoModelId()));
        }
        resp.setEpisodeCount(ordered.size());
        resp.setCreatedAt(series.getCreatedAt());
        resp.setUpdatedAt(series.getUpdatedAt());
        if (!ordered.isEmpty()) {
            resp.setLatestEpisode(toEpisodeResp(ordered.get(ordered.size() - 1), false));
        }
        if (withEpisodes) {
            resp.setEpisodes(ordered.stream().map(item -> toEpisodeResp(item, true)).toList());
        }
        return resp;
    }

    /** 每个项目取「第一个已出图的场景」作列表封面；没有场景图则不设封面。 */
    private Map<Long, String> sceneCoverKeys(Collection<Long> seriesIds) {
        if (seriesIds == null || seriesIds.isEmpty()) {
            return Map.of();
        }
        List<DramaAsset> scenes = assetMapper.selectList(new LambdaQueryWrapper<DramaAsset>()
                .in(DramaAsset::getSeriesId, seriesIds)
                .eq(DramaAsset::getType, DramaContract.ASSET_TYPE_SCENE)
                .orderByAsc(DramaAsset::getEpisodeId)
                .orderByAsc(DramaAsset::getId));
        Map<Long, String> covers = new LinkedHashMap<>();
        for (DramaAsset asset : scenes) {
            if (asset.getSeriesId() == null
                    || !DramaContract.ASSET_TYPE_SCENE.equals(asset.getType())
                    || !StringUtils.hasText(asset.getImageStorageKey())) {
                continue;
            }
            covers.putIfAbsent(asset.getSeriesId(), asset.getImageStorageKey());
        }
        return covers;
    }

    private DramaEpisodeResp toEpisodeResp(DramaEpisode episode, boolean withBody) {
        DramaEpisodeResp resp = new DramaEpisodeResp();
        resp.setId(episode.getId());
        resp.setSeriesId(episode.getSeriesId());
        resp.setEpisodeNumber(episode.getEpisodeNumber());
        resp.setName(episode.getName());
        resp.setStatus(episode.getStatus());
        resp.setEngine(StringUtils.hasText(episode.getEngine())
                ? episode.getEngine()
                : DramaContract.ENGINE_PENDING);
        resp.setInheritedFromEpisodeId(episode.getInheritedFromEpisodeId());
        resp.setCreatedAt(episode.getCreatedAt());
        resp.setUpdatedAt(episode.getUpdatedAt());
        boolean analyzing = analyzeLock != null && analyzeLock.isActive(episode);
        resp.setAnalyzing(analyzing);
        resp.setAnalyzingAt(analyzing ? episode.getScriptAnalyzingAt() : null);
        if (withBody) {
            resp.setSourceText(episode.getSourceText());
            resp.setScript(episode.getScript());
            boolean serviceAvailable = textService.getIfAvailable() != null;
            resp.setTextServiceAvailable(serviceAvailable);
            resp.setCanGenerate(serviceAvailable && textChatGateway.llmAvailable(episode.getSeriesId()));
            applyFilm(resp, episode);
        }
        return resp;
    }

    private void applyFilm(DramaEpisodeResp resp, DramaEpisode episode) {
        if (filmService == null) {
            return;
        }
        var film = filmService.preview(episode);
        resp.setFilmUrl(film.getFilmUrl());
        resp.setFilmStale(film.getStale());
    }

    private void applyParseIfPresent(DramaEpisode episode, String raw) {
        DramaTextService svc = textService.getIfAvailable();
        if (svc == null) {
            episode.setEngine(DramaContract.ENGINE_PENDING);
            return;
        }
        ScriptDoc doc = svc.parseScript(episode.getSeriesId(), raw);
        episode.setScript(ScriptDocs.toMap(doc));
        episode.setEngine(DramaContract.normalizeEngine(svc.engine(episode.getSeriesId())));
    }

    private DramaTextService requireTextService() {
        DramaTextService svc = textService.getIfAvailable();
        if (svc == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "剧本生成服务尚未就绪");
        }
        return svc;
    }

    /**
     * 锁定后再改剧本：本集回到待确认，资产/章节/分镜标已过期。
     */
    private void onScriptMutated(DramaEpisode episode) {
        String status = episode.getStatus();
        if (DramaContract.STATUS_LOCKED.equals(status) || DramaContract.STATUS_EXPIRED.equals(status)) {
            episode.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
            expireDownstream(episode.getId());
        }
    }

    /**
     * 把本集下游资产、章节、分镜标为已过期；分镜 compiled_prompt 作废。
     */
    void expireDownstream(Long episodeId) {
        if (episodeId == null) {
            return;
        }
        List<DramaAsset> assets = assetMapper.selectList(new LambdaQueryWrapper<DramaAsset>()
                .eq(DramaAsset::getEpisodeId, episodeId));
        if (assets != null) {
            for (DramaAsset asset : assets) {
                if (DramaContract.STATUS_EXPIRED.equals(asset.getStatus())) {
                    continue;
                }
                asset.setStatus(DramaContract.STATUS_EXPIRED);
                assetMapper.updateById(asset);
            }
        }
        storyboardService.expireByEpisode(episodeId);
    }

    private void touchSeries(Long seriesId) {
        DramaSeries series = seriesMapper.selectById(seriesId);
        if (series != null) {
            seriesMapper.updateById(series);
        }
    }

    static String normalizeAspectRatio(String value) {
        String ratio = StringUtils.hasText(value) ? value.trim() : DramaContract.DEFAULT_ASPECT_RATIO;
        if (!DramaContract.ASPECT_RATIOS.contains(ratio)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "画幅仅支持 16:9、9:16、1:1");
        }
        return ratio;
    }

    static String normalizeResolution(String value) {
        String resolution = StringUtils.hasText(value) ? value.trim().toUpperCase() : DramaContract.DEFAULT_RESOLUTION;
        if (!DramaContract.RESOLUTIONS.contains(resolution)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "清晰度仅支持 480P、720P");
        }
        return resolution;
    }

    static int normalizeDuration(Integer value) {
        int duration = value == null ? DramaContract.DEFAULT_DURATION_SEC : value;
        if (!DramaContract.DURATIONS.contains(duration)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "单镜时长仅支持 5–15 秒");
        }
        return duration;
    }

    static int normalizePlanned(Integer value) {
        int planned = value == null ? DramaContract.DEFAULT_PLANNED_EPISODES : value;
        if (planned < 1 || planned > 99) {
            throw new BizException(ErrorCode.BAD_REQUEST, "规划集数须在 1–99 之间");
        }
        return planned;
    }

    public List<DramaStyleResp> listStyles() {
        return DramaStyles.all().stream().map(spec -> {
            DramaStyleResp resp = new DramaStyleResp();
            resp.setKey(spec.key());
            resp.setName(spec.name());
            resp.setCover(spec.cover());
            return resp;
        }).toList();
    }

    public List<DramaImageModelResp> listEnabledImageModels() {
        UserContext.requireUserId();
        return aiModelMapper.selectList(new LambdaQueryWrapper<AiModel>()
                        .eq(AiModel::getCategory, ModelCategory.IMAGE)
                        .eq(AiModel::getEnabled, true)
                        .orderByAsc(AiModel::getId))
                .stream()
                .filter(model -> GenerateRequests.supportsMode(
                        model.getCapabilities(), ModelCategory.IMAGE, GenerateMode.T2I))
                .map(DramaService::toImageModelResp)
                .toList();
    }

    @Transactional
    public DramaSeriesResp bindImageModel(Long seriesId, DramaImageModelBindReq req) {
        DramaSeries series = requireSeries(seriesId);
        AiModel model = requireEnabledImageModel(req.getImageModelId());
        series.setImageModelId(model.getId());
        series.setImageAdapterKey(AdapterCatalog.resolveKey(
                model.getAdapterKey(), model.getProvider(), model.getCategory(), model.getModelKey()));
        seriesMapper.updateById(series);
        return getSeries(series.getId());
    }

    public List<DramaVideoModelResp> listEnabledVideoModels() {
        UserContext.requireUserId();
        return aiModelMapper.selectList(new LambdaQueryWrapper<AiModel>()
                        .eq(AiModel::getCategory, ModelCategory.VIDEO)
                        .eq(AiModel::getEnabled, true)
                        .orderByAsc(AiModel::getId))
                .stream()
                .filter(model -> GenerateRequests.supportsMode(
                        model.getCapabilities(), ModelCategory.VIDEO, GenerateMode.R2V))
                .map(DramaService::toVideoModelResp)
                .toList();
    }

    @Transactional
    public DramaSeriesResp bindVideoModel(Long seriesId, DramaVideoModelBindReq req) {
        DramaSeries series = requireSeries(seriesId);
        AiModel model = requireEnabledVideoModel(req.getVideoModelId());
        series.setVideoModelId(model.getId());
        series.setVideoAdapterKey(AdapterCatalog.resolveKeyForGenerate(
                model.getAdapterKey(), model.getProvider(), model.getCategory(), model.getModelKey()));
        seriesMapper.updateById(series);
        return getSeries(series.getId());
    }

    @Transactional
    public DramaSeriesResp bindTextModel(Long seriesId, DramaTextModelBindReq req) {
        DramaSeries series = requireSeries(seriesId);
        if (req.getTextModelId() == null) {
            seriesMapper.update(null, new UpdateWrapper<DramaSeries>()
                    .eq("id", series.getId())
                    .set("text_model_id", null));
        } else {
            series.setTextModelId(requireEnabledTextModel(req.getTextModelId()).getId());
            seriesMapper.updateById(series);
        }
        return getSeries(series.getId());
    }

    public List<DramaTextModelResp> listEnabledTextModels() {
        UserContext.requireUserId();
        return aiModelMapper.selectList(new LambdaQueryWrapper<AiModel>()
                        .eq(AiModel::getCategory, ModelCategory.TEXT)
                        .eq(AiModel::getEnabled, true)
                        .orderByAsc(AiModel::getId))
                .stream()
                .map(DramaService::toTextModelResp)
                .toList();
    }

    private Long resolveCreateTextModelId(Long requested) {
        if (requested == null) {
            return null;
        }
        return requireEnabledTextModel(requested).getId();
    }

    private AiModel requireEnabledTextModel(Long id) {
        AiModel model = aiModelMapper.selectById(id);
        if (model == null || !ModelCategory.TEXT.equalsIgnoreCase(model.getCategory())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文本模型不存在");
        }
        if (!Boolean.TRUE.equals(model.getEnabled())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "所选文本模型已停用");
        }
        if (!TextChatGatewayImpl.usable(model)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "所选文本模型缺少 Base URL 或无法识别适配协议，请到管理端补全后重试");
        }
        return model;
    }

    private static List<Long> idsOf(Long... ids) {
        if (ids == null || ids.length == 0) {
            return List.of();
        }
        List<Long> values = new java.util.ArrayList<>();
        for (Long id : ids) {
            if (id != null) {
                values.add(id);
            }
        }
        return values;
    }

    private Map<Long, String> modelNames(Collection<Long> ids) {
        Set<Long> wanted = new HashSet<>();
        for (Long id : ids) {
            if (id != null) {
                wanted.add(id);
            }
        }
        if (wanted.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (AiModel model : aiModelMapper.selectBatchIds(wanted)) {
            names.put(model.getId(), model.getName());
        }
        return names;
    }

    private static DramaImageModelResp toImageModelResp(AiModel model) {
        DramaImageModelResp resp = new DramaImageModelResp();
        resp.setId(model.getId());
        resp.setName(model.getName());
        resp.setProvider(model.getProvider());
        resp.setAdapterKey(model.getAdapterKey());
        return resp;
    }

    private AiModel requireEnabledImageModel(Long id) {
        AiModel model = aiModelMapper.selectById(id);
        if (model == null || !ModelCategory.IMAGE.equalsIgnoreCase(model.getCategory())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "图像模型不存在");
        }
        if (!Boolean.TRUE.equals(model.getEnabled())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "所选图像模型已停用");
        }
        if (!GenerateRequests.supportsMode(model.getCapabilities(), ModelCategory.IMAGE, GenerateMode.T2I)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "所选图像模型不支持文生图");
        }
        return model;
    }

    private static DramaVideoModelResp toVideoModelResp(AiModel model) {
        DramaVideoModelResp resp = new DramaVideoModelResp();
        resp.setId(model.getId());
        resp.setName(model.getName());
        resp.setProvider(model.getProvider());
        resp.setAdapterKey(model.getAdapterKey());
        resp.setDurations(DramaDurations.supported(model.getAdapterKey(), model.getModelKey()));
        resp.setResolutions(DramaResolutions.supported(model.getAdapterKey(), model.getModelKey()));
        return resp;
    }

    private AiModel requireEnabledVideoModel(Long id) {
        AiModel model = aiModelMapper.selectById(id);
        if (model == null || !ModelCategory.VIDEO.equalsIgnoreCase(model.getCategory())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "视频模型不存在");
        }
        if (!Boolean.TRUE.equals(model.getEnabled())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "所选视频模型已停用");
        }
        if (!GenerateRequests.supportsMode(model.getCapabilities(), ModelCategory.VIDEO, GenerateMode.R2V)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "所选视频模型不支持参考生视频");
        }
        return model;
    }

    private static DramaTextModelResp toTextModelResp(AiModel model) {
        DramaTextModelResp resp = new DramaTextModelResp();
        resp.setId(model.getId());
        resp.setName(model.getName());
        resp.setProvider(model.getProvider());
        return resp;
    }

    private static void applyStyle(DramaSeries series, String styleKey) {
        DramaStyles.Spec spec = DramaStyles.require(styleKey);
        series.setStyleKey(spec.key());
        series.setStylePrefix(spec.prefix());
    }

    /**
     * 未锁定资产的提示词里替换风格句，避免分析后改风格只改了系列字段。
     */
    void rewriteUnlockedAssetStyle(Long seriesId, String previousPrefix, String nextPrefix) {
        if (seriesId == null || !StringUtils.hasText(nextPrefix)
                || java.util.Objects.equals(previousPrefix, nextPrefix)) {
            return;
        }
        List<DramaAsset> assets = assetMapper.selectList(new LambdaQueryWrapper<DramaAsset>()
                .eq(DramaAsset::getSeriesId, seriesId));
        for (DramaAsset asset : assets) {
            if (asset == null || DramaContract.STATUS_LOCKED.equals(asset.getStatus())) {
                continue;
            }
            boolean changed = false;
            String prompt = replaceStylePrefix(asset.getImagePrompt(), previousPrefix, nextPrefix);
            if (!java.util.Objects.equals(prompt, asset.getImagePrompt())) {
                asset.setImagePrompt(prompt);
                changed = true;
            }
            String description = replaceStylePrefix(asset.getDescription(), previousPrefix, nextPrefix);
            if (!java.util.Objects.equals(description, asset.getDescription())) {
                asset.setDescription(description);
                changed = true;
            }
            if (!changed) {
                continue;
            }
            if (DramaContract.STATUS_NOT_STARTED.equals(asset.getStatus())
                    || DramaContract.STATUS_EXPIRED.equals(asset.getStatus())) {
                asset.setStatus(DramaContract.STATUS_PENDING_CONFIRM);
            }
            assetMapper.updateById(asset);
        }
    }

    static String replaceStylePrefix(String text, String previousPrefix, String nextPrefix) {
        if (!StringUtils.hasText(text) || !StringUtils.hasText(nextPrefix)) {
            return text;
        }
        String next = text;
        if (StringUtils.hasText(previousPrefix) && next.contains(previousPrefix)) {
            next = next.replace(previousPrefix, nextPrefix);
        }
        for (DramaStyles.Spec spec : DramaStyles.all()) {
            if (!nextPrefix.equals(spec.prefix()) && next.contains(spec.prefix())) {
                next = next.replace(spec.prefix(), nextPrefix);
            }
        }
        return next;
    }
}
