package cn.aigc.dit.server.service;

import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.server.drama.DramaFilmModes;
import cn.aigc.dit.server.drama.DramaFilms;
import cn.aigc.dit.server.drama.entity.DramaEpisode;
import cn.aigc.dit.server.drama.entity.DramaSeries;
import cn.aigc.dit.server.drama.entity.DramaShot;
import cn.aigc.dit.server.drama.mapper.DramaEpisodeMapper;
import cn.aigc.dit.server.drama.mapper.DramaSeriesMapper;
import cn.aigc.dit.server.drama.mapper.DramaShotMapper;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.entity.Creation;
import cn.aigc.dit.server.entity.CreationSession;
import cn.aigc.dit.server.entity.GenTask;
import cn.aigc.dit.server.generate.GenerateRequests;
import cn.aigc.dit.server.generate.GenerateTaskViews;
import cn.aigc.dit.server.mapper.AiModelMapper;
import cn.aigc.dit.server.mapper.CreationMapper;
import cn.aigc.dit.server.mapper.CreationSessionMapper;
import cn.aigc.dit.server.mapper.GenTaskMapper;
import cn.aigc.dit.server.media.MediaUrls;
import cn.aigc.dit.server.model.req.CreationSessionCreateReq;
import cn.aigc.dit.server.model.req.CreationSessionRenameReq;
import cn.aigc.dit.server.model.resp.CreationItemResp;
import cn.aigc.dit.server.model.resp.CreationProjectResp;
import cn.aigc.dit.server.model.resp.CreationSessionResp;
import cn.aigc.dit.server.model.resp.GenerateTaskResp;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CreationService extends ServiceImpl<CreationMapper, Creation> {

    private final CreationSessionMapper sessionMapper;
    private final GenTaskMapper genTaskMapper;
    private final AiModelMapper aiModelMapper;
    private final AssetService assetService;
    private final DramaSeriesMapper dramaSeriesMapper;
    private final DramaEpisodeMapper dramaEpisodeMapper;
    private final DramaShotMapper dramaShotMapper;
    private final MediaRetentionService mediaRetention;

    public CreationService(CreationSessionMapper sessionMapper,
                           GenTaskMapper genTaskMapper,
                           AiModelMapper aiModelMapper,
                           AssetService assetService,
                           DramaSeriesMapper dramaSeriesMapper,
                           DramaEpisodeMapper dramaEpisodeMapper,
                           DramaShotMapper dramaShotMapper,
                           MediaRetentionService mediaRetention) {
        this.sessionMapper = sessionMapper;
        this.genTaskMapper = genTaskMapper;
        this.aiModelMapper = aiModelMapper;
        this.assetService = assetService;
        this.dramaSeriesMapper = dramaSeriesMapper;
        this.dramaEpisodeMapper = dramaEpisodeMapper;
        this.dramaShotMapper = dramaShotMapper;
        this.mediaRetention = mediaRetention;
    }

    public List<CreationSessionResp> listSessions() {
        Long userId = UserContext.requireUserId();
        List<CreationSession> sessions = sessionMapper.selectList(new LambdaQueryWrapper<CreationSession>()
                .eq(CreationSession::getUserId, userId)
                .orderByDesc(CreationSession::getUpdatedAt));
        Set<Long> dramaSessions = dramaBoundSessionIds(userId);
        return sessions.stream()
                .filter(session -> !dramaSessions.contains(session.getId()))
                .map(session -> toSessionResp(session, false))
                .toList();
    }

    public CreationSessionResp getSession(Long id) {
        CreationSession session = requireSession(id);
        return toSessionResp(session, true);
    }

    @Transactional
    public CreationSessionResp createSession(CreationSessionCreateReq req) {
        Long userId = UserContext.requireUserId();
        CreationSession session = new CreationSession();
        session.setUserId(userId);
        session.setTitle(StringUtils.hasText(req.getTitle()) ? req.getTitle().trim() : "未命名创作");
        session.setCategory(normalizeCategory(req.getCategory()));
        sessionMapper.insert(session);
        return toSessionResp(session, false);
    }

    @Transactional
    public CreationSessionResp renameSession(Long id, CreationSessionRenameReq req) {
        CreationSession session = requireSession(id);
        session.setTitle(req.getTitle().trim());
        sessionMapper.updateById(session);
        return toSessionResp(session, false);
    }

    @Transactional
    public void deleteSession(Long id) {
        CreationSession session = requireSession(id);
        lambdaUpdate()
                .eq(Creation::getSessionId, session.getId())
                .eq(Creation::getUserId, session.getUserId())
                .setSql("session_id = NULL")
                .update();
        sessionMapper.deleteById(session.getId());
    }

    CreationSession requireSession(Long id) {
        CreationSession session = sessionMapper.selectById(id);
        if (session == null || !session.getUserId().equals(UserContext.requireUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        return session;
    }

    @Transactional
    public CreationSession ensureSession(Long sessionId, Long userId, String category, String prompt) {
        if (sessionId != null) {
            CreationSession session = sessionMapper.selectById(sessionId);
            if (session == null || !session.getUserId().equals(userId)) {
                throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
            }
            if (StringUtils.hasText(prompt) && (session.getTitle() == null || "未命名创作".equals(session.getTitle())
                    || "新建创作".equals(session.getTitle()))) {
                session.setTitle(titleFromPrompt(prompt));
            }
            if (StringUtils.hasText(category)) {
                session.setCategory(category);
            }
            sessionMapper.updateById(session);
            return session;
        }
        CreationSession session = new CreationSession();
        session.setUserId(userId);
        session.setCategory(category);
        session.setTitle(titleFromPrompt(prompt));
        sessionMapper.insert(session);
        return session;
    }

    /** 会话标题：去掉模型侧 Picture/Video/Audio 锚点后再截取。 */
    static String titleFromPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "未命名创作";
        }
        String text = prompt
                .replaceAll("(?i)[（(]\\s*<Picture\\s+\\d+>\\s*[)）]", " ")
                .replaceAll("(?i)\\s*=\\s*<Picture\\s+\\d+>", " ")
                .replaceAll("(?i)<(Picture|Video|Audio)\\s+\\d+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (text.isBlank()) {
            return "未命名创作";
        }
        return text.length() > 12 ? text.substring(0, 12) : text;
    }

    public List<CreationItemResp> listMine(String category, String query, String project, OffsetDateTime from, OffsetDateTime to) {
        Long userId = UserContext.requireUserId();
        ProjectFilter filter = ProjectFilter.parse(project);
        LambdaQueryWrapper<Creation> wrapper = new LambdaQueryWrapper<Creation>()
                .eq(Creation::getUserId, userId)
                .eq(StringUtils.hasText(category) && !"all".equalsIgnoreCase(category), Creation::getCategory, category)
                .and(StringUtils.hasText(query), w -> w.like(Creation::getTitle, query).or().like(Creation::getPrompt, query))
                .ge(from != null, Creation::getCreatedAt, from)
                .lt(to != null, Creation::getCreatedAt, to)
                .and(filter.none(), w -> w.isNull(Creation::getProjectKind).or().isNull(Creation::getProjectId))
                .eq(!filter.all() && !filter.none(), Creation::getProjectKind, filter.kind())
                .eq(!filter.all() && !filter.none() && filter.id() != null, Creation::getProjectId, filter.id())
                .orderByDesc(Creation::getCreatedAt);
        List<Creation> items = list(wrapper);
        return toGallery(items);
    }

    public List<CreationProjectResp> listProjects() {
        Long userId = UserContext.requireUserId();
        List<Creation> items = list(new LambdaQueryWrapper<Creation>()
                .eq(Creation::getUserId, userId)
                .orderByDesc(Creation::getCreatedAt));
        Map<String, CreationProjectResp> projects = new LinkedHashMap<>();
        boolean unassigned = false;
        for (Creation item : items) {
            if (!StringUtils.hasText(item.getProjectKind()) || item.getProjectId() == null) {
                unassigned = true;
                continue;
            }
            String key = item.getProjectKind() + ":" + item.getProjectId();
            projects.computeIfAbsent(key, ignored -> {
                CreationProjectResp resp = new CreationProjectResp();
                resp.setKey(key);
                resp.setKind(item.getProjectKind());
                resp.setId(item.getProjectId());
                return resp;
            });
        }
        Map<Long, String> sessionNames = loadSessionNames(projects.values().stream()
                .filter(item -> "quick".equals(item.getKind()))
                .map(CreationProjectResp::getId)
                .toList());
        Map<Long, String> seriesNames = loadSeriesNames(projects.values().stream()
                .filter(item -> "drama".equals(item.getKind()))
                .map(CreationProjectResp::getId)
                .toList());
        for (CreationProjectResp projectItem : projects.values()) {
            if ("drama".equals(projectItem.getKind())) {
                projectItem.setName(seriesNames.getOrDefault(projectItem.getId(), "短剧项目"));
            } else {
                projectItem.setName(sessionNames.getOrDefault(projectItem.getId(), "未命名创作"));
            }
        }
        List<CreationProjectResp> result = new ArrayList<>(projects.values());
        if (unassigned) {
            CreationProjectResp none = new CreationProjectResp();
            none.setKey("none");
            none.setKind("none");
            none.setName("未归入项目");
            result.add(none);
        }
        return result;
    }

    public CreationItemResp getMine(Long id) {
        Creation creation = requireMine(id);
        return toGallery(List.of(creation)).get(0);
    }

    /**
     * 成片不是单次模型生成。比例、清晰度和视频模型取自短剧项目，时长取拼接后实际保留的秒数。
     */
    @Transactional
    public void recordDramaFilm(DramaEpisode episode, Integer durationSec) {
        if (episode == null || episode.getUserId() == null || !StringUtils.hasText(episode.getFilmKey())) {
            return;
        }
        DramaSeries series = episode.getSeriesId() == null ? null : dramaSeriesMapper.selectById(episode.getSeriesId());
        String seriesName = series == null || !StringUtils.hasText(series.getName()) ? "短剧" : series.getName().trim();
        String title = filmTitle(seriesName, episode);
        Creation existing = getOne(new LambdaQueryWrapper<Creation>()
                .eq(Creation::getUserId, episode.getUserId())
                .eq(Creation::getStorageKey, episode.getFilmKey())
                .last("LIMIT 1"), false);
        if (existing != null) {
            existing.setPosterKey(episode.getFilmPosterKey());
            existing.setTitle(title);
            existing.setPrompt(title);
            existing.setProjectKind("drama");
            existing.setProjectId(episode.getSeriesId());
            applyFilmFacts(existing, series, durationSec, true);
            updateById(existing);
            return;
        }
        Creation creation = new Creation();
        creation.setUserId(episode.getUserId());
        creation.setTitle(title);
        creation.setCategory(ModelCategory.VIDEO);
        creation.setStorageKey(episode.getFilmKey());
        creation.setPosterKey(episode.getFilmPosterKey());
        creation.setPrompt(title);
        creation.setCreditCost(0L);
        creation.setCopyIndex(1);
        creation.setProjectKind("drama");
        creation.setProjectId(episode.getSeriesId());
        applyFilmFacts(creation, series, durationSec, true);
        save(creation);
    }

    @Transactional
    public void removeDramaFilm(Long userId, String storageKey) {
        if (userId == null || !StringUtils.hasText(storageKey)) {
            return;
        }
        remove(new LambdaQueryWrapper<Creation>()
                .eq(Creation::getUserId, userId)
                .eq(Creation::getStorageKey, storageKey));
    }

    /**
     * 短剧资产图或分镜视频从项目里删除时，同步去掉「我的创作」里同一条成品。
     * 只删创作记录，不删对象文件，也不改资产、分镜或成片。
     * 必须同时命中用户、生成任务和文件，避免误删上传图、素材库引用，或其他创作。
     */
    @Transactional
    public void removeDramaOutputs(Long userId, Long taskId, String storageKey) {
        if (userId == null || taskId == null || !StringUtils.hasText(storageKey)) {
            return;
        }
        remove(new LambdaQueryWrapper<Creation>()
                .eq(Creation::getUserId, userId)
                .eq(Creation::getTaskId, taskId)
                .eq(Creation::getStorageKey, storageKey));
    }

    /** 删除整个短剧项目时，去掉该项目在「我的创作」里的图片、视频和成片。 */
    @Transactional
    public void removeDramaProject(Long userId, Long seriesId) {
        if (userId == null || seriesId == null) {
            return;
        }
        remove(new LambdaQueryWrapper<Creation>()
                .eq(Creation::getUserId, userId)
                .eq(Creation::getProjectKind, "drama")
                .eq(Creation::getProjectId, seriesId));
    }

    /** 删除单集时，只去掉该集媒体对应的短剧创作，不影响其他项目或快速创作。 */
    @Transactional
    public void removeDramaMedia(Long userId, java.util.Collection<String> storageKeys) {
        List<String> keys = storageKeys == null ? List.of() : storageKeys.stream()
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (userId == null || keys.isEmpty()) {
            return;
        }
        remove(new LambdaQueryWrapper<Creation>()
                .eq(Creation::getUserId, userId)
                .eq(Creation::getProjectKind, "drama")
                .in(Creation::getStorageKey, keys));
    }

    public cn.aigc.dit.server.model.resp.AssetResp saveToAsset(Long creationId) {
        Creation creation = requireMine(creationId);
        String name = StringUtils.hasText(creation.getTitle()) ? creation.getTitle() : "作品 " + creation.getId();
        return assetService.importFromStorage(creation.getUserId(), name, creation.getCategory(),
                creation.getStorageKey(), creation.getPosterKey());
    }

    /**
     * 只从「我的创作」移除这条记录，不改短剧资产、分镜和成片。
     * 若短剧和素材库都不再引用同一文件，再清理对象存储。
     */
    @Transactional
    public void deleteMine(Long id) {
        Creation creation = requireMine(id);
        removeById(creation.getId());
        mediaRetention.purgeAfterCommit(creation.getUserId(),
                java.util.stream.Stream.of(creation.getStorageKey(), creation.getPosterKey())
                        .filter(StringUtils::hasText)
                        .toList());
    }

    private Creation requireMine(Long id) {
        Creation creation = getById(id);
        if (creation == null || !creation.getUserId().equals(UserContext.requireUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "作品不存在");
        }
        return creation;
    }

    public GenerateTaskResp toTaskResp(GenTask task, List<Creation> creations, String modelName) {
        GenerateTaskResp resp = new GenerateTaskResp();
        resp.setId(task.getId());
        resp.setModelId(task.getModelId());
        resp.setModelName(modelName);
        resp.setCategory(task.getCategory());
        resp.setStatus(GenerateTaskViews.publicStatus(task));
        resp.setProgress(task.getProgress());
        resp.setErrorMessage(GenerateTaskViews.publicError(task));
        resp.setPrompt(task.getPrompt());
        resp.setParams(task.getParams());
        resp.setCopies(task.getCopies());
        resp.setCreditCost(task.getCreditCost());
        resp.setOutputStorageKey(task.getOutputStorageKey());
        resp.setPosterKey(task.getPosterKey());
        if (task.getParams() != null && task.getParams().get("tailKey") != null) {
            resp.setTailKey(String.valueOf(task.getParams().get("tailKey")));
        }
        resp.setOutputKeys(task.getOutputKeys());
        resp.setCreatedAt(task.getCreatedAt());
        resp.setFinishedAt(GenerateTaskViews.shouldKeepPolling(task) ? null : task.getFinishedAt());
        if (task.getParams() != null && task.getParams().get("mode") != null) {
            resp.setMode(String.valueOf(task.getParams().get("mode")));
        }
        if (task.getParams() != null && task.getParams().get("sessionId") != null) {
            Object sessionId = task.getParams().get("sessionId");
            if (sessionId instanceof Number number) {
                resp.setSessionId(number.longValue());
            }
        }
        if (creations != null) {
            resp.setCreations(creations.stream().map(item -> toItem(item, modelName)).toList());
            if (resp.getSessionId() == null && !creations.isEmpty()) {
                resp.setSessionId(creations.get(0).getSessionId());
            }
        }
        return resp;
    }

    public CreationItemResp toItem(Creation creation, String modelName) {
        CreationItemResp resp = new CreationItemResp();
        resp.setId(creation.getId());
        resp.setSessionId(creation.getSessionId());
        resp.setTaskId(creation.getTaskId());
        resp.setModelId(creation.getModelId());
        resp.setModelName(modelName);
        resp.setTitle(creation.getTitle());
        resp.setCategory(creation.getCategory());
        resp.setType(mediaType(creation.getStorageKey(), creation.getCategory()));
        resp.setFormat(fileFormat(creation.getStorageKey()));
        resp.setStorageKey(creation.getStorageKey());
        resp.setPosterKey(creation.getPosterKey());
        resp.setUrl(MediaUrls.of(creation.getStorageKey()));
        resp.setSrc(MediaUrls.of(creation.getStorageKey()));
        String poster = creation.getPosterKey() == null ? creation.getStorageKey() : creation.getPosterKey();
        resp.setPosterUrl(MediaUrls.of(poster));
        resp.setPoster(MediaUrls.of(poster));
        resp.setPrompt(creation.getPrompt());
        resp.setRatio(creation.getRatio());
        resp.setResolution(creation.getResolution());
        resp.setDurationSec(creation.getDurationSec());
        resp.setDuration(creation.getDurationSec() == null ? "—" : creation.getDurationSec() + " 秒");
        resp.setCreditCost(creation.getCreditCost());
        resp.setCopyIndex(creation.getCopyIndex());
        resp.setCreatedAt(creation.getCreatedAt());
        return resp;
    }

    private CreationSessionResp toSessionResp(CreationSession session, boolean withTasks) {
        CreationSessionResp resp = new CreationSessionResp();
        resp.setId(session.getId());
        resp.setTitle(session.getTitle());
        resp.setCategory(session.getCategory());
        resp.setCreatedAt(session.getCreatedAt());
        resp.setUpdatedAt(session.getUpdatedAt());
        if (!withTasks) {
            return resp;
        }
        List<Creation> creations = list(new LambdaQueryWrapper<Creation>()
                .eq(Creation::getSessionId, session.getId())
                .orderByAsc(Creation::getCreatedAt));
        List<Long> taskIds = creations.stream().map(Creation::getTaskId).filter(Objects::nonNull).distinct().toList();
        List<GenTask> extraTasks = genTaskMapper.selectList(new LambdaQueryWrapper<GenTask>()
                .eq(GenTask::getUserId, session.getUserId())
                .apply("params->>'sessionId' = {0}", String.valueOf(session.getId()))
                .orderByAsc(GenTask::getCreatedAt));
        Map<Long, List<Creation>> byTask = creations.stream()
                .filter(item -> item.getTaskId() != null)
                .collect(Collectors.groupingBy(Creation::getTaskId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, GenTask> tasks = new LinkedHashMap<>();
        if (!taskIds.isEmpty()) {
            for (GenTask task : genTaskMapper.selectBatchIds(taskIds)) {
                tasks.put(task.getId(), task);
            }
        }
        for (GenTask extra : extraTasks) {
            tasks.putIfAbsent(extra.getId(), extra);
        }
        Map<Long, String> modelNames = loadModelNames(tasks.values().stream().map(GenTask::getModelId).toList());
        List<GenerateTaskResp> taskResps = new ArrayList<>();
        for (GenTask task : tasks.values()) {
            taskResps.add(toTaskResp(task, byTask.getOrDefault(task.getId(), List.of()), modelNameOf(modelNames, task.getModelId())));
        }
        taskResps.sort((a, b) -> {
            OffsetDateTime left = a.getCreatedAt() == null ? OffsetDateTime.MIN : a.getCreatedAt();
            OffsetDateTime right = b.getCreatedAt() == null ? OffsetDateTime.MIN : b.getCreatedAt();
            return left.compareTo(right);
        });
        resp.setTasks(taskResps);
        return resp;
    }

    private List<CreationItemResp> toGallery(List<Creation> items) {
        if (items.isEmpty()) {
            return List.of();
        }
        hydrateDramaFilms(items);
        Map<Long, GenTask> tasks = loadTasks(items.stream().map(Creation::getTaskId).toList());
        Map<Long, String> modelNames = loadModelNames(items.stream().map(Creation::getModelId).toList());
        Map<Long, String> sessionNames = loadSessionNames(items.stream()
                .filter(item -> "quick".equals(item.getProjectKind()))
                .map(Creation::getProjectId)
                .toList());
        Map<Long, String> seriesNames = loadSeriesNames(items.stream()
                .filter(item -> "drama".equals(item.getProjectKind()))
                .map(Creation::getProjectId)
                .toList());
        return items.stream().map(item -> {
            CreationItemResp resp = toItem(item, modelNames.get(item.getModelId()));
            GenTask task = item.getTaskId() == null ? null : tasks.get(item.getTaskId());
            resp.setElapsed(task == null ? "—" : elapsedLabel(task));
            fillProject(resp, item, sessionNames, seriesNames);
            return resp;
        }).toList();
    }

    private void fillProject(CreationItemResp resp, Creation creation, Map<Long, String> sessionNames, Map<Long, String> seriesNames) {
        String kind = creation.getProjectKind();
        Long projectId = creation.getProjectId();
        resp.setProjectKind(kind);
        resp.setProjectId(projectId);
        if (!StringUtils.hasText(kind) || projectId == null) {
            resp.setProjectKey("none");
            resp.setProjectName("未归入项目");
            return;
        }
        resp.setProjectKey(kind + ":" + projectId);
        if ("drama".equals(kind)) {
            resp.setProjectName(seriesNames.getOrDefault(projectId, "短剧项目"));
        } else {
            resp.setProjectName(sessionNames.getOrDefault(projectId, "未命名创作"));
        }
    }

    private Map<Long, GenTask> loadTasks(List<Long> ids) {
        List<Long> taskIds = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (taskIds.isEmpty()) {
            return new HashMap<>();
        }
        return genTaskMapper.selectBatchIds(taskIds).stream()
                .filter(item -> item.getId() != null)
                .collect(Collectors.toMap(GenTask::getId, item -> item, (a, b) -> a, HashMap::new));
    }

    private Map<Long, String> loadSessionNames(List<Long> ids) {
        List<Long> sessionIds = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (sessionIds.isEmpty()) {
            return new HashMap<>();
        }
        return sessionMapper.selectBatchIds(sessionIds).stream()
                .filter(item -> item.getId() != null)
                .collect(Collectors.toMap(CreationSession::getId, item -> StringUtils.hasText(item.getTitle()) ? item.getTitle() : "未命名创作", (a, b) -> a, HashMap::new));
    }

    private Map<Long, String> loadSeriesNames(List<Long> ids) {
        List<Long> seriesIds = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (seriesIds.isEmpty()) {
            return new HashMap<>();
        }
        return dramaSeriesMapper.selectBatchIds(seriesIds).stream()
                .filter(item -> item.getId() != null)
                .collect(Collectors.toMap(DramaSeries::getId, item -> StringUtils.hasText(item.getName()) ? item.getName() : "短剧项目", (a, b) -> a, HashMap::new));
    }

    /**
     * 已入库、但当时没写下模型或规格的成片，按当前项目和入片镜头补齐，让列表直接可见。
     * 补过一次就落库，避免每次打开都重算。
     */
    private void hydrateDramaFilms(List<Creation> items) {
        List<Creation> pending = items.stream().filter(CreationService::needsFilmFacts).toList();
        if (pending.isEmpty()) {
            return;
        }
        Map<Long, DramaSeries> seriesById = loadSeriesById(pending.stream().map(Creation::getProjectId).toList());
        Map<String, DramaEpisode> episodeByKey = loadEpisodesByFilmKey(pending.stream().map(Creation::getStorageKey).toList());
        Map<Long, List<DramaShot>> shotsByEpisode = loadShotsByEpisode(
                episodeByKey.values().stream().map(DramaEpisode::getId).toList());
        for (Creation film : pending) {
            DramaSeries series = film.getProjectId() == null ? null : seriesById.get(film.getProjectId());
            DramaEpisode episode = film.getStorageKey() == null ? null : episodeByKey.get(film.getStorageKey());
            List<DramaShot> shots = episode == null
                    ? List.of()
                    : shotsByEpisode.getOrDefault(episode.getId(), List.of());
            Integer duration = film.getDurationSec() != null && film.getDurationSec() > 0
                    ? null
                    : filmDurationSec(episode, shots);
            if (applyFilmFacts(film, series, duration, false)) {
                updateById(film);
            }
        }
    }

    private Map<Long, DramaSeries> loadSeriesById(List<Long> ids) {
        List<Long> seriesIds = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (seriesIds.isEmpty()) {
            return Map.of();
        }
        return dramaSeriesMapper.selectBatchIds(seriesIds).stream()
                .filter(item -> item.getId() != null)
                .collect(Collectors.toMap(DramaSeries::getId, item -> item, (a, b) -> a, HashMap::new));
    }

    private Map<String, DramaEpisode> loadEpisodesByFilmKey(List<String> keys) {
        List<String> filmKeys = keys.stream().filter(StringUtils::hasText).distinct().toList();
        if (filmKeys.isEmpty()) {
            return Map.of();
        }
        return dramaEpisodeMapper.selectList(new LambdaQueryWrapper<DramaEpisode>()
                        .in(DramaEpisode::getFilmKey, filmKeys)).stream()
                .filter(item -> StringUtils.hasText(item.getFilmKey()))
                .collect(Collectors.toMap(DramaEpisode::getFilmKey, item -> item, (a, b) -> a, HashMap::new));
    }

    private Map<Long, List<DramaShot>> loadShotsByEpisode(List<Long> ids) {
        List<Long> episodeIds = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (episodeIds.isEmpty()) {
            return Map.of();
        }
        return dramaShotMapper.selectList(new LambdaQueryWrapper<DramaShot>()
                        .in(DramaShot::getEpisodeId, episodeIds)).stream()
                .filter(item -> item.getEpisodeId() != null)
                .collect(Collectors.groupingBy(DramaShot::getEpisodeId, HashMap::new, Collectors.toList()));
    }

    static boolean isDramaFilm(Creation creation) {
        return creation != null
                && "drama".equals(creation.getProjectKind())
                && creation.getTaskId() == null
                && creation.getStorageKey() != null
                && creation.getStorageKey().endsWith("/film.mp4");
    }

    static boolean needsFilmFacts(Creation creation) {
        return isDramaFilm(creation) && (
                creation.getModelId() == null
                        || !StringUtils.hasText(creation.getRatio())
                        || !StringUtils.hasText(creation.getResolution())
                        || creation.getDurationSec() == null
                        || creation.getDurationSec() <= 0);
    }

    static boolean applyFilmFacts(Creation creation, DramaSeries series, Integer durationSec, boolean overwrite) {
        boolean changed = false;
        if (series != null && series.getVideoModelId() != null
                && (overwrite || creation.getModelId() == null)
                && !series.getVideoModelId().equals(creation.getModelId())) {
            creation.setModelId(series.getVideoModelId());
            changed = true;
        }
        String ratio = filmRatio(series);
        if (ratio != null && (overwrite || !StringUtils.hasText(creation.getRatio())) && !ratio.equals(creation.getRatio())) {
            creation.setRatio(ratio);
            changed = true;
        }
        String resolution = series == null || !StringUtils.hasText(series.getDefaultResolution())
                ? null
                : series.getDefaultResolution().trim();
        if (resolution != null && !resolution.isEmpty()
                && (overwrite || !StringUtils.hasText(creation.getResolution()))
                && !resolution.equals(creation.getResolution())) {
            creation.setResolution(resolution);
            changed = true;
        }
        if (durationSec != null && durationSec > 0
                && (overwrite || creation.getDurationSec() == null || creation.getDurationSec() <= 0)
                && !durationSec.equals(creation.getDurationSec())) {
            creation.setDurationSec(durationSec);
            changed = true;
        }
        return changed;
    }

    static String filmRatio(DramaSeries series) {
        if (series == null || !StringUtils.hasText(series.getAspectRatio())) {
            return null;
        }
        String ratio = series.getAspectRatio().trim().replace('：', ':');
        return ratio.isEmpty() ? null : ratio;
    }

    /** 按成片当时的衔接方式，把入片镜头裁完后的时长加总。缺入片清单时用本集已出片镜头。 */
    static Integer filmDurationSec(DramaEpisode episode, List<DramaShot> shots) {
        List<DramaShot> included = includedFilmShots(episode, shots);
        if (included.isEmpty()) {
            return null;
        }
        String mode = DramaFilms.assembledMode(episode);
        if (!StringUtils.hasText(mode)) {
            mode = DramaFilmModes.DIRECT;
        }
        int kept = DramaFilmModes.totalKeptSec(DramaFilmModes.plan(mode, included, null, null));
        return kept > 0 ? kept : null;
    }

    private static List<DramaShot> includedFilmShots(DramaEpisode episode, List<DramaShot> shots) {
        if (shots == null || shots.isEmpty()) {
            return List.of();
        }
        List<Long> ids = episode == null ? null : episode.getFilmShotIds();
        if (ids == null || ids.isEmpty()) {
            return DramaFilms.clips(shots);
        }
        Map<Long, DramaShot> byId = new HashMap<>();
        for (DramaShot shot : shots) {
            if (shot != null && shot.getId() != null) {
                byId.putIfAbsent(shot.getId(), shot);
            }
        }
        List<DramaShot> included = new ArrayList<>();
        for (Long id : ids) {
            DramaShot shot = byId.get(id);
            if (shot != null) {
                included.add(shot);
            }
        }
        return included;
    }

    private static String filmTitle(String seriesName, DramaEpisode episode) {
        String episodeName = StringUtils.hasText(episode.getName())
                ? episode.getName().trim()
                : "第" + (episode.getEpisodeNumber() == null ? 1 : episode.getEpisodeNumber()) + "集";
        String title = seriesName + " · " + episodeName + " · 成片";
        return title.length() > 80 ? title.substring(0, 80) : title;
    }

    record ProjectFilter(String kind, Long id, boolean all, boolean none) {
        static ProjectFilter parse(String project) {
            if (!StringUtils.hasText(project) || "all".equalsIgnoreCase(project)) {
                return new ProjectFilter(null, null, true, false);
            }
            if ("none".equalsIgnoreCase(project)) {
                return new ProjectFilter(null, null, false, true);
            }
            if ("quick".equals(project) || "drama".equals(project)) {
                return new ProjectFilter(project, null, false, false);
            }
            int split = project.indexOf(':');
            if (split <= 0 || split >= project.length() - 1) {
                return new ProjectFilter(null, null, true, false);
            }
            String kind = project.substring(0, split);
            if (!"quick".equals(kind) && !"drama".equals(kind)) {
                return new ProjectFilter(null, null, true, false);
            }
            try {
                return new ProjectFilter(kind, Long.parseLong(project.substring(split + 1)), false, false);
            } catch (NumberFormatException e) {
                return new ProjectFilter(null, null, true, false);
            }
        }
    }

    private Set<Long> dramaBoundSessionIds(Long userId) {
        List<GenTask> tasks = genTaskMapper.selectList(new LambdaQueryWrapper<GenTask>()
                .eq(GenTask::getUserId, userId)
                .apply("params->>'source' = {0}", GenerateRequests.SOURCE_DRAMA));
        Set<Long> ids = new HashSet<>();
        for (GenTask task : tasks) {
            Long sessionId = GenerateRequests.sessionIdFromParams(task.getParams());
            if (sessionId != null) {
                ids.add(sessionId);
            }
        }
        return ids;
    }

    private Map<Long, String> loadModelNames(List<Long> ids) {
        List<Long> modelIds = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (modelIds.isEmpty()) {
            return new HashMap<>();
        }
        return aiModelMapper.selectBatchIds(modelIds).stream()
                .filter(item -> item.getId() != null)
                .collect(Collectors.toMap(AiModel::getId, item -> item.getName() == null ? "" : item.getName(), (a, b) -> a, HashMap::new));
    }

    static String modelNameOf(Map<Long, String> modelNames, Long modelId) {
        if (modelNames == null || modelId == null) {
            return null;
        }
        return modelNames.get(modelId);
    }

    static String normalizeCategory(String category) {
        if (ModelCategory.VIDEO.equalsIgnoreCase(category)) {
            return ModelCategory.VIDEO;
        }
        if (ModelCategory.IMAGE.equalsIgnoreCase(category)) {
            return ModelCategory.IMAGE;
        }
        if (ModelCategory.AUDIO.equalsIgnoreCase(category)) {
            return ModelCategory.AUDIO;
        }
        return null;
    }

    static String mediaType(String storageKey, String category) {
        if (storageKey != null) {
            String lower = storageKey.toLowerCase();
            if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp") || lower.endsWith(".gif")) {
                return ModelCategory.IMAGE;
            }
            if (lower.endsWith(".mp4") || lower.endsWith(".webm")) {
                return ModelCategory.VIDEO;
            }
            if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".m4a")
                    || lower.endsWith(".aac") || lower.endsWith(".flac") || lower.endsWith(".opus")
                    || lower.endsWith(".pcm")) {
                return ModelCategory.AUDIO;
            }
        }
        return category;
    }

    static String fileFormat(String storageKey) {
        if (!StringUtils.hasText(storageKey)) {
            return null;
        }
        String path = storageKey.split("[?#]", 2)[0];
        int extensionIndex = path.lastIndexOf('.');
        if (extensionIndex < 0 || extensionIndex == path.length() - 1 || extensionIndex < path.lastIndexOf('/')) {
            return null;
        }
        return path.substring(extensionIndex + 1).toUpperCase();
    }

    public static String elapsedLabel(GenTask task) {
        if (task.getCreatedAt() == null) {
            return "—";
        }
        OffsetDateTime end = task.getFinishedAt() == null ? OffsetDateTime.now() : task.getFinishedAt();
        long seconds = Math.max(0, Duration.between(task.getCreatedAt(), end).getSeconds());
        return formatElapsed(seconds);
    }

    static String formatElapsed(long totalSeconds) {
        if (totalSeconds < 60) {
            return totalSeconds + "秒";
        }
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            if (minutes == 0 && seconds == 0) {
                return hours + "小时";
            }
            if (seconds == 0) {
                return hours + "小时" + minutes + "分";
            }
            return hours + "小时" + minutes + "分" + seconds + "秒";
        }
        if (seconds == 0) {
            return minutes + "分";
        }
        return minutes + "分" + seconds + "秒";
    }
}
