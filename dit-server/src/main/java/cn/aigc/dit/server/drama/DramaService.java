package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.server.drama.entity.DramaEpisode;
import cn.aigc.dit.server.drama.entity.DramaSeries;
import cn.aigc.dit.server.drama.mapper.DramaEpisodeMapper;
import cn.aigc.dit.server.drama.mapper.DramaSeriesMapper;
import cn.aigc.dit.server.drama.model.req.DramaEpisodeUpdateReq;
import cn.aigc.dit.server.drama.model.req.DramaSeriesCreateReq;
import cn.aigc.dit.server.drama.model.req.DramaSeriesUpdateReq;
import cn.aigc.dit.server.drama.model.resp.DramaEpisodeResp;
import cn.aigc.dit.server.drama.model.resp.DramaSeriesResp;
import cn.aigc.dit.server.drama.model.resp.DramaStyleResp;
import cn.aigc.dit.server.drama.model.resp.DramaTextModelResp;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.mapper.AiModelMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DramaService {

    private final DramaSeriesMapper seriesMapper;
    private final DramaEpisodeMapper episodeMapper;
    private final AiModelMapper aiModelMapper;

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
        Map<Long, String> textModelNames = textModelNames(series.stream().map(DramaSeries::getTextModelId).toList());
        return series.stream()
                .map(item -> toSeriesResp(item, bySeries.getOrDefault(item.getId(), List.of()), false, textModelNames))
                .toList();
    }

    public DramaSeriesResp getSeries(Long id) {
        DramaSeries series = requireSeries(id);
        List<DramaEpisode> episodes = episodeMapper.selectList(new LambdaQueryWrapper<DramaEpisode>()
                .eq(DramaEpisode::getSeriesId, series.getId())
                .orderByAsc(DramaEpisode::getEpisodeNumber));
        return toSeriesResp(series, episodes, true, textModelNames(idsOf(series.getTextModelId())));
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
        episodeMapper.insert(episode);
        return toSeriesResp(series, List.of(episode), true, textModelNames(idsOf(series.getTextModelId())));
    }

    @Transactional
    public DramaSeriesResp updateSeries(Long id, DramaSeriesUpdateReq req) {
        DramaSeries series = requireSeries(id);
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
        return getSeries(series.getId());
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
                                         Map<Long, String> textModelNames) {
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
        resp.setAspectRatio(series.getAspectRatio());
        resp.setDefaultResolution(series.getDefaultResolution());
        resp.setDefaultDurationSec(series.getDefaultDurationSec());
        resp.setPlannedEpisodeCount(series.getPlannedEpisodeCount());
        resp.setTextModelId(series.getTextModelId());
        if (series.getTextModelId() != null) {
            resp.setTextModelName(textModelNames.get(series.getTextModelId()));
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

    private DramaEpisodeResp toEpisodeResp(DramaEpisode episode, boolean withBody) {
        DramaEpisodeResp resp = new DramaEpisodeResp();
        resp.setId(episode.getId());
        resp.setSeriesId(episode.getSeriesId());
        resp.setEpisodeNumber(episode.getEpisodeNumber());
        resp.setName(episode.getName());
        resp.setStatus(episode.getStatus());
        resp.setInheritedFromEpisodeId(episode.getInheritedFromEpisodeId());
        resp.setCreatedAt(episode.getCreatedAt());
        resp.setUpdatedAt(episode.getUpdatedAt());
        if (withBody) {
            resp.setSourceText(episode.getSourceText());
            resp.setScript(episode.getScript());
        }
        return resp;
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
            throw new BizException(ErrorCode.BAD_REQUEST, "单镜时长仅支持 5、10、15 秒");
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
        long enabled = aiModelMapper.selectCount(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getCategory, ModelCategory.TEXT)
                .eq(AiModel::getEnabled, true));
        if (requested == null) {
            if (enabled > 0) {
                throw new BizException(ErrorCode.BAD_REQUEST, "请选择文本模型");
            }
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
        return model;
    }

    private static List<Long> idsOf(Long id) {
        return id == null ? List.of() : List.of(id);
    }

    private Map<Long, String> textModelNames(Collection<Long> ids) {
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
}
