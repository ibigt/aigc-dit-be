package cn.aigc.dit.server.service;

import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.common.security.UserContext;
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
import cn.aigc.dit.server.model.resp.CreationItemResp;
import cn.aigc.dit.server.model.resp.CreationSessionResp;
import cn.aigc.dit.server.model.resp.GenerateTaskResp;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class CreationService extends ServiceImpl<CreationMapper, Creation> {

    private final CreationSessionMapper sessionMapper;
    private final GenTaskMapper genTaskMapper;
    private final AiModelMapper aiModelMapper;
    private final AssetService assetService;

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
                session.setTitle(prompt.trim().length() > 12 ? prompt.trim().substring(0, 12) : prompt.trim());
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
        String title = prompt == null ? "未命名创作" : prompt.trim();
        session.setTitle(title.length() > 12 ? title.substring(0, 12) : (title.isEmpty() ? "未命名创作" : title));
        sessionMapper.insert(session);
        return session;
    }

    public List<CreationItemResp> listMine(String category, String query) {
        Long userId = UserContext.requireUserId();
        LambdaQueryWrapper<Creation> wrapper = new LambdaQueryWrapper<Creation>()
                .eq(Creation::getUserId, userId)
                .eq(StringUtils.hasText(category) && !"all".equalsIgnoreCase(category), Creation::getCategory, category)
                .and(StringUtils.hasText(query), w -> w.like(Creation::getTitle, query).or().like(Creation::getPrompt, query))
                .orderByDesc(Creation::getCreatedAt);
        List<Creation> items = list(wrapper);
        items = excludeDramaCreations(items);
        Map<Long, String> modelNames = loadModelNames(items.stream().map(Creation::getModelId).toList());
        return items.stream().map(item -> toItem(item, modelNames.get(item.getModelId()))).toList();
    }

    public CreationItemResp getMine(Long id) {
        Creation creation = requireMine(id);
        String modelName = null;
        if (creation.getModelId() != null) {
            AiModel model = aiModelMapper.selectById(creation.getModelId());
            modelName = model == null ? null : model.getName();
        }
        return toItem(creation, modelName);
    }

    public cn.aigc.dit.server.model.resp.AssetResp saveToAsset(Long creationId) {
        Creation creation = requireMine(creationId);
        String name = StringUtils.hasText(creation.getTitle()) ? creation.getTitle() : "作品 " + creation.getId();
        return assetService.importFromStorage(creation.getUserId(), name, creation.getCategory(),
                creation.getStorageKey(), creation.getPosterKey());
    }

    @Transactional
    public void deleteMine(Long id) {
        Creation creation = requireMine(id);
        removeById(creation.getId());
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

    private List<Creation> excludeDramaCreations(List<Creation> items) {
        Set<Long> taskIds = items.stream().map(Creation::getTaskId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (taskIds.isEmpty()) {
            return items;
        }
        Set<Long> dramaTaskIds = new HashSet<>();
        for (GenTask task : genTaskMapper.selectBatchIds(taskIds)) {
            if (GenerateRequests.isDramaSource(task.getParams())) {
                dramaTaskIds.add(task.getId());
            }
        }
        if (dramaTaskIds.isEmpty()) {
            return items;
        }
        return items.stream()
                .filter(item -> item.getTaskId() == null || !dramaTaskIds.contains(item.getTaskId()))
                .toList();
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
        }
        return category;
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
