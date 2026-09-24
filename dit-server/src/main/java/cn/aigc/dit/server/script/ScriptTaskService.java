package cn.aigc.dit.server.script;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.server.drama.DramaService;
import cn.aigc.dit.server.drama.model.req.DramaSeriesCreateReq;
import cn.aigc.dit.server.drama.model.req.DramaScriptSaveReq;
import cn.aigc.dit.server.drama.model.resp.DramaSeriesResp;
import cn.aigc.dit.server.drama.text.TextChatRequest;
import cn.aigc.dit.server.drama.text.TextChatResult;
import cn.aigc.dit.server.entity.AiModel;
import cn.aigc.dit.server.mapper.AiModelMapper;
import cn.aigc.dit.server.script.entity.ScriptMessage;
import cn.aigc.dit.server.script.entity.ScriptTask;
import cn.aigc.dit.server.script.entity.ScriptTaskVersion;
import cn.aigc.dit.server.script.mapper.ScriptMessageMapper;
import cn.aigc.dit.server.script.mapper.ScriptTaskMapper;
import cn.aigc.dit.server.script.mapper.ScriptTaskVersionMapper;
import cn.aigc.dit.server.script.model.req.ScriptChatReq;
import cn.aigc.dit.server.script.model.req.ScriptDocumentSaveReq;
import cn.aigc.dit.server.script.model.req.ScriptModelUpdateReq;
import cn.aigc.dit.server.script.model.req.ScriptTaskCreateReq;
import cn.aigc.dit.server.script.model.req.ScriptTitleUpdateReq;
import cn.aigc.dit.server.script.model.resp.ScriptMessageResp;
import cn.aigc.dit.server.script.model.resp.ScriptTaskResp;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptTaskService {

    private static final int MAX_CONTEXT_MESSAGES = 12;
    private static final int NOVEL_CHUNK_SIZE = 8_000;
    /** 32K 上下文模型必须同时容纳输入和输出，不能把完整窗口都声明成输出。 */
    private static final int GENERATION_MAX_TOKENS = 12_000;
    private static final int SUMMARY_MAX_TOKENS = 4_000;
    private static final int ASSISTANT_MAX_TOKENS = 6_000;
    private static final int GENERATION_SOURCE_MAX_CHARS = 16_000;
    private static final Pattern EPISODE_MARKER = Pattern.compile(
            "(?m)^\\s*(?:第\\s*(\\d+)\\s*集|第\\s*([一二三四五六七八九十百]+)\\s*集|EP\\s*(\\d+)).*$");

    private final ScriptTaskMapper taskMapper;
    private final ScriptMessageMapper messageMapper;
    private final ScriptTaskVersionMapper versionMapper;
    private final AiModelMapper modelMapper;
    private final ObjectMapper objectMapper;
    private final ScriptLlmGateway llmGateway;
    private final DramaService dramaService;

    public List<ScriptTaskResp> list(String query) {
        Long userId = UserContext.requireUserId();
        List<ScriptTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<ScriptTask>()
                .eq(ScriptTask::getUserId, userId)
                .and(StringUtils.hasText(query), wrapper -> wrapper
                        .like(ScriptTask::getTitle, query)
                        .or().like(ScriptTask::getGenre, query))
                .orderByDesc(ScriptTask::getUpdatedAt));
        return tasks.stream().map(task -> toResp(task, false)).toList();
    }

    public ScriptTaskResp get(Long id) {
        return toResp(requireTask(id), true);
    }

    @Transactional
    public ScriptTaskResp create(ScriptTaskCreateReq req) {
        String mode = normalizeMode(req.getMode());
        ScriptTask task = new ScriptTask();
        task.setUserId(UserContext.requireUserId());
        task.setTitle(StringUtils.hasText(req.getTitle()) ? req.getTitle().trim() : defaultTitle(mode));
        task.setMode(mode);
        task.setStatus("DRAFT");
        task.setGenre(trimToNull(req.getGenre()));
        task.setTargetEpisodes(req.getTargetEpisodes() == null ? 6 : req.getTargetEpisodes());
        task.setModelId(resolveModelId(req.getModelId()));
        task.setInputText(req.getInputText());
        task.setDocument("");
        task.setAttachments(req.getAttachments() == null ? List.of() : req.getAttachments());
        taskMapper.insert(task);
        return toResp(task, true);
    }

    @Transactional
    public ScriptTaskResp saveDocument(Long id, ScriptDocumentSaveReq req) {
        ScriptTask task = requireTask(id);
        String next = req.getDocument() == null ? "" : req.getDocument();
        if (Objects.equals(task.getDocument(), next)) {
            return toResp(task, true);
        }
        saveDocumentVersion(task, next);
        task.setDocument(next);
        task.setStatus("DRAFT");
        taskMapper.updateById(task);
        return toResp(task, true);
    }

    private void saveDocumentVersion(ScriptTask task, String document) {
        int nextVersion = versionMapper.selectCount(new LambdaQueryWrapper<ScriptTaskVersion>()
                .eq(ScriptTaskVersion::getTaskId, task.getId())).intValue() + 1;
        ScriptTaskVersion version = new ScriptTaskVersion();
        version.setTaskId(task.getId());
        version.setUserId(task.getUserId());
        version.setVersionNo(nextVersion);
        version.setDocument(document == null ? "" : document);
        version.setCreatedAt(OffsetDateTime.now());
        versionMapper.insert(version);
    }

    @Transactional
    public ScriptTaskResp complete(Long id) {
        ScriptTask task = requireTask(id);
        if (!StringUtils.hasText(task.getDocument())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先生成或填写剧本正文");
        }
        if (!"SUCCEEDED".equals(task.getStatus())) {
            task.setStatus("SUCCEEDED");
            taskMapper.updateById(task);
        }
        return toResp(task, true);
    }

    @Transactional
    public void delete(Long id) {
        ScriptTask task = requireTask(id);
        taskMapper.deleteById(task.getId());
    }

    /** 为模型只返回正文的历史任务补全人物设定和分集大纲。 */
    @Transactional
    public ScriptTaskResp repairStructure(Long id) {
        ScriptTask task = requireTask(id);
        if (!StringUtils.hasText(task.getDocument())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先生成剧本正文");
        }
        Map<String, Object> current = task.getResultJson() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(task.getResultJson());
        Map<String, Object> repaired = normalizeResult(task,
                mergeStructure(current, repairStructure(task, task.getDocument())));
        if (!hasStructure(repaired)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "模型未返回人物设定和分集大纲，请稍后重试");
        }
        applyGeneratedTitle(task, repaired);
        Object repairedScript = repaired.get("script_markdown");
        String repairedDocument = repairedScript != null && StringUtils.hasText(String.valueOf(repairedScript))
                ? String.valueOf(repairedScript) : task.getDocument();
        repairedDocument = limitEpisodes(repairedDocument,
                task.getTargetEpisodes() == null ? 1 : task.getTargetEpisodes());
        repaired.put("script_markdown", repairedDocument);
        task.setResultJson(repaired);
        task.setDocument(repairedDocument);
        taskMapper.updateById(task);
        return toResp(task, true);
    }

    /** 续写下一集，并同步正文、分集大纲、人物设定和故事摘要。 */
    @Transactional
    public ScriptTaskResp continueEpisode(Long id) {
        ScriptTask task = requireTask(id);
        if (!StringUtils.hasText(task.getDocument())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先生成或填写第1集正文");
        }
        int targetEpisodes = task.getTargetEpisodes() == null ? 1 : task.getTargetEpisodes();
        int currentEpisode = lastDocumentEpisode(task.getDocument());
        if (currentEpisode >= targetEpisodes) {
            throw new BizException(ErrorCode.BAD_REQUEST, "已完成目标集数，无需继续续写");
        }
        int nextEpisode = currentEpisode + 1;
        Map<String, Object> current = normalizeResult(task, task.getResultJson() == null
                ? new LinkedHashMap<>() : task.getResultJson());
        String system = "你是竖屏短剧续写编剧。只续写指定的一集，并只返回严格 JSON，不要输出 Markdown 代码围栏。";
        String user = "剧本名称：" + task.getTitle()
                + "\n目标集数：" + targetEpisodes
                + "\n本次只续写第" + nextEpisode + "集，不得输出其他集。"
                + "\n请保持既有人物动机、世界观和叙事节奏；如人物设定或故事设定发生变化，请在 JSON 中返回更新后的完整 characters、logline。"
                + "\nJSON Schema：{\"episode\":" + nextEpisode
                + ",\"title\":\"\",\"logline\":\"\",\"characters\":[{\"name\":\"\",\"role\":\"\",\"arc\":\"\"}],"
                + "\"outline_item\":{\"episode\":" + nextEpisode + ",\"title\":\"\",\"hook\":\"\",\"summary\":\"\"},"
                + "\"script_markdown\":\"第" + nextEpisode + "集标准分场剧本正文\"}"
                + "\n当前故事摘要：" + String.valueOf(current.getOrDefault("logline", "暂无"))
                + "\n当前人物设定：" + String.valueOf(current.getOrDefault("characters", List.of()))
                + "\n已有分集大纲：" + String.valueOf(current.getOrDefault("outline", List.of()))
                + "\n已完成正文：\n" + truncate(task.getDocument(), GENERATION_SOURCE_MAX_CHARS);
        TextChatResult response = llmGateway.chat(task.getModelId(),
                new TextChatRequest(system, user, true, GENERATION_MAX_TOKENS));
        Map<String, Object> generated = normalizeResult(task, parseResult(response.getContent()));
        Object generatedScript = generated.get("script_markdown");
        if (generatedScript == null || !StringUtils.hasText(String.valueOf(generatedScript))) {
            throw new BizException(ErrorCode.BAD_REQUEST, "模型未返回续写正文，请稍后重试");
        }
        String episodeDocument = extractEpisodeBlock(String.valueOf(generatedScript), nextEpisode);
        if (!StringUtils.hasText(episodeDocument)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "模型未返回有效的第" + nextEpisode + "集正文，请稍后重试");
        }
        if (!startsWithEpisodeMarker(episodeDocument, nextEpisode)) {
            episodeDocument = "第" + nextEpisode + "集\n\n" + episodeDocument.trim();
        }

        Map<String, Object> merged = new LinkedHashMap<>(current);
        replaceNonEmptyStructure(merged, generated, "title", "logline", "characters", "evaluation");
        List<Map<String, Object>> outline = new ArrayList<>(normalizeList(merged.get("outline"), Integer.MAX_VALUE));
        Map<String, Object> nextOutline = continuationOutlineItem(generated, nextEpisode);
        if (nextOutline != null) {
            boolean replaced = false;
            for (int i = 0; i < outline.size(); i++) {
                if (episodeValue(outline.get(i).get("episode")) == nextEpisode) {
                    outline.set(i, nextOutline);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) outline.add(nextOutline);
        }
        merged.put("outline", normalizeList(outline, targetEpisodes));
        String document = task.getDocument().trim() + "\n\n" + episodeDocument.trim();
        merged.put("script_markdown", limitEpisodes(document, targetEpisodes));
        task.setResultJson(normalizeResult(task, merged));
        applyGeneratedTitle(task, task.getResultJson());
        task.setDocument(String.valueOf(task.getResultJson().get("script_markdown")));
        task.setStatus("SUCCEEDED");
        saveDocumentVersion(task, task.getDocument());
        taskMapper.updateById(task);
        saveMessage(task, "assistant", episodeDocument, "已续写第" + nextEpisode + "集，并同步更新正文、分集大纲和人物设定。");
        return toResp(task, true);
    }

    @Transactional
    public ScriptTaskResp updateModel(Long id, ScriptModelUpdateReq req) {
        ScriptTask task = requireTask(id);
        task.setModelId(resolveModelId(req.getModelId()));
        taskMapper.updateById(task);
        return toResp(task, true);
    }

    @Transactional
    public ScriptTaskResp updateTitle(Long id, ScriptTitleUpdateReq req) {
        ScriptTask task = requireTask(id);
        task.setTitle(req.getTitle().trim());
        taskMapper.updateById(task);
        return toResp(task, true);
    }

    /** 结构化三模式工作流：小说较长时先分片提炼，再进入最终改编。 */
    public ScriptTaskResp generate(Long id) {
        ScriptTask task = requireTask(id);
        if (!StringUtils.hasText(task.getInputText())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先输入创意、小说原文或待评估剧本");
        }
        task.setStatus("RUNNING");
        taskMapper.updateById(task);
        try {
            saveMessage(task, "user", task.getInputText(), null);
            String source = task.getInputText();
            if ("adapt".equals(task.getMode()) && source.length() > NOVEL_CHUNK_SIZE) {
                source = summarizeNovelChunks(task, source);
            }
            TextChatResult response = llmGateway.chat(task.getModelId(),
                    new TextChatRequest(systemPrompt(task), generationPrompt(task, source), true,
                            GENERATION_MAX_TOKENS));
            Map<String, Object> result = normalizeResult(task, parseResult(response.getContent()));
            String document = documentFromResult(task, result);
            if (!hasStructure(result)) {
                try {
                    result = mergeStructure(result, repairStructure(task, document));
                    document = documentFromResult(task, result);
                } catch (RuntimeException repairError) {
                    log.warn("script structure repair skipped, taskId={}", task.getId(), repairError);
                }
            }
            applyGeneratedTitle(task, result);
            task.setResultJson(result);
            task.setDocument(document);
            task.setStatus("SUCCEEDED");
            saveDocumentVersion(task, task.getDocument());
            taskMapper.updateById(task);
            saveMessage(task, "assistant", task.getDocument(),
                    "已按" + task.getMode() + "工作流完成结构化生成，并将结果同步到剧本正文。");
            return toResp(task, true);
        } catch (RuntimeException ex) {
            task.setStatus("FAILED");
            taskMapper.updateById(task);
            throw ex;
        }
    }

    /** 普通 HTTP 对话保留给非流式客户端；正式前端使用 streamChat。 */
    public String chat(Long id, ScriptChatReq req) {
        ScriptTask task = requireTask(id);
        saveMessage(task, "user", req.getMessage(), null);
        TextChatResult result = callAssistant(task, req.getMessage());
        saveMessage(task, "assistant", result.getContent(), Boolean.TRUE.equals(req.getThinking())
                ? "已读取当前剧本上下文，并按人物动机、冲突推进和短剧节奏进行推演。" : null);
        return result.getContent();
    }

    public SseEmitter streamChat(Long id, ScriptChatReq req) {
        ScriptTask task = requireTask(id);
        saveMessage(task, "user", req.getMessage(), null);
        SseEmitter emitter = new SseEmitter(600_000L);
        CompletableFuture.runAsync(() -> {
            try {
                send(emitter, "thinking", Map.of("stage", "正在读取剧本上下文", "detail", "整理人物关系、情绪曲线与当前任务"));
                TextChatResult result = callAssistant(task, req.getMessage());
                String content = result.getContent() == null ? "" : result.getContent();
                send(emitter, "thinking", Map.of("stage", "正在组织修改建议", "detail", "将建议落到可执行的台词、场景和钩子"));
                for (int i = 0; i < content.length(); i += 24) {
                    send(emitter, "delta", Map.of("text", content.substring(i, Math.min(i + 24, content.length()))));
                }
                String thinking = Boolean.TRUE.equals(req.getThinking())
                        ? "已读取当前剧本上下文，并按人物动机、冲突推进和短剧节奏进行推演。" : null;
                saveMessage(task, "assistant", content, thinking);
                send(emitter, "done", Map.of("content", content));
                emitter.complete();
            } catch (Exception ex) {
                log.warn("script stream chat failed, taskId={}", id, ex);
                try {
                    send(emitter, "error", Map.of("message", ex.getMessage() == null ? "对话失败" : ex.getMessage()));
                } catch (IOException ignored) {
                    // client already disconnected
                }
                emitter.completeWithError(ex);
            }
        });
        return emitter;
    }

    @Transactional
    public DramaSeriesResp createDrama(Long id) {
        ScriptTask task = requireTask(id);
        if (task.getDramaSeriesId() != null) {
            return dramaService.getSeries(task.getDramaSeriesId());
        }
        if (!StringUtils.hasText(task.getDocument())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先生成或填写剧本正文后再创建短剧");
        }
        if (!"SUCCEEDED".equals(task.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "剧本已保存为草稿，请先点击“完成剧本”后再创建短剧");
        }
        DramaSeriesCreateReq req = new DramaSeriesCreateReq();
        req.setName(StringUtils.hasText(task.getTitle()) ? task.getTitle() : "未命名短剧");
        req.setStyleKey("live-realism");
        req.setAspectRatio("9:16");
        req.setDefaultResolution("720P");
        req.setDefaultDurationSec(10);
        req.setPlannedEpisodeCount(task.getTargetEpisodes());
        req.setTextModelId(task.getModelId());
        DramaSeriesResp series = dramaService.createSeries(req);
        if (series.getEpisodes() == null || series.getEpisodes().isEmpty()
                || series.getEpisodes().get(0).getId() == null) {
            throw new BizException(ErrorCode.INTERNAL, "短剧首集创建失败，无法导入剧本");
        }
        DramaScriptSaveReq scriptReq = new DramaScriptSaveReq();
        scriptReq.setSourceText(task.getDocument());
        dramaService.saveScript(series.getEpisodes().get(0).getId(), scriptReq);
        task.setDramaSeriesId(series.getId());
        taskMapper.updateById(task);
        return dramaService.getSeries(series.getId());
    }

    private TextChatResult callAssistant(ScriptTask task, String latest) {
        List<ScriptMessage> messages = messageMapper.selectList(new LambdaQueryWrapper<ScriptMessage>()
                .eq(ScriptMessage::getTaskId, task.getId())
                .orderByDesc(ScriptMessage::getCreatedAt)
                .last("LIMIT " + MAX_CONTEXT_MESSAGES));
        List<ScriptMessage> ordered = new ArrayList<>(messages);
        java.util.Collections.reverse(ordered);
        StringBuilder context = new StringBuilder();
        for (ScriptMessage message : ordered) {
            context.append("user".equals(message.getRole()) ? "用户：" : "光驭精灵：")
                    .append(message.getContent()).append("\n");
        }
        String document = task.getDocument() == null ? "" : task.getDocument();
        String user = "当前剧本：\n" + truncate(document, 10_000)
                + "\n\n最近对话：\n" + truncate(context.toString(), 4_000)
                + "\n\n本轮请求：\n" + truncate(latest, 4_000);
        return llmGateway.chat(task.getModelId(), new TextChatRequest(
                "你是光驭精灵，一名熟悉竖屏短剧结构、人物弧光和台词潜台词的中文编剧助手。回答要具体、可执行，支持 Markdown。不要编造已调用的外部工具。",
                user, false, ASSISTANT_MAX_TOKENS));
    }

    private String summarizeNovelChunks(ScriptTask task, String source) {
        StringBuilder summaries = new StringBuilder();
        for (int from = 0, index = 1; from < source.length(); from += NOVEL_CHUNK_SIZE, index++) {
            String chunk = source.substring(from, Math.min(from + NOVEL_CHUNK_SIZE, source.length()));
            TextChatResult result = llmGateway.chat(task.getModelId(), new TextChatRequest(
                    "你是小说改编前置分析器。只提炼事实，不续写，不评价。",
                    "请提炼第 " + index + " 个文本片段的章节冲突、人物关系、关键对白、旁白信息和场景转换：\n" + chunk,
                    false, SUMMARY_MAX_TOKENS));
            summaries.append("片段 ").append(index).append("：\n").append(result.getContent()).append("\n\n");
        }
        return summaries.toString();
    }

    private String systemPrompt(ScriptTask task) {
        return switch (task.getMode()) {
            case "idea" -> "你是专业短剧策划。根据创意生成标准短剧大纲与第1集分场。所有字段必须输出，结果只能是 JSON。";
            case "adapt" -> "你是小说改编编剧。识别对话、旁白、场景转换和核心冲突，压缩成长节奏竖屏短剧。所有字段必须输出，结果只能是 JSON。";
            case "review" -> "你是剧本开发顾问。使用人物弧光、黄金三秒钩子、情绪爽点、商业价值四项 Rubric 评估。所有字段必须输出，结果只能是 JSON。";
            default -> "你是专业短剧编剧，结果只能是 JSON。";
        };
    }

    private String generationPrompt(ScriptTask task, String source) {
        int episodes = task.getTargetEpisodes() == null ? 1 : task.getTargetEpisodes();
        String schema = "{\"title\":\"\",\"logline\":\"\",\"characters\":[{\"name\":\"\",\"role\":\"\",\"arc\":\"\"}],\"outline\":[{\"episode\":1,\"title\":\"\",\"hook\":\"\",\"summary\":\"\"}],\"opening_scene\":\"标准分场格式：场次/时间/地点/动作/对白/旁白\",\"script_markdown\":\"只写第1集可直接拍摄的剧本正文\",\"evaluation\":{\"character_arc\":0,\"hook\":0,\"emotion\":0,\"commercial\":0,\"strategy\":\"\"}}";
        return "题材：" + (task.getGenre() == null ? "未指定" : task.getGenre())
                + "；目标集数：" + episodes
                + "。请严格遵守：outline 必须恰好输出 " + episodes + " 项；script_markdown 只输出第1集正文，绝对不要继续写第2集或其他集；characters 至少输出主要人物。请保留可拍摄细节，避免空泛建议。\n输出 JSON Schema：" + schema
                + "\n用户输入：\n" + truncate(source, GENERATION_SOURCE_MAX_CHARS);
    }

    private Map<String, Object> repairStructure(ScriptTask task, String document) {
        int episodes = task.getTargetEpisodes() == null ? 1 : task.getTargetEpisodes();
        String system = "你是短剧项目结构整理器。请基于给定剧本正文提取主要人物和分集大纲，只返回严格 JSON，不要输出 Markdown。";
        String user = "目标集数：" + episodes
                + "。outline 必须恰好输出目标集数项；characters 至少输出主要人物。"
                + "\nJSON Schema：{\"title\":\"\",\"logline\":\"\",\"characters\":[{\"name\":\"\",\"role\":\"\",\"arc\":\"\"}],\"outline\":[{\"episode\":1,\"title\":\"\",\"hook\":\"\",\"summary\":\"\"}]}"
                + "\n剧本正文：\n" + truncate(document, GENERATION_SOURCE_MAX_CHARS);
        TextChatResult result = llmGateway.chat(task.getModelId(),
                new TextChatRequest(system, user, true, SUMMARY_MAX_TOKENS));
        return normalizeResult(task, parseResult(result.getContent()));
    }

    private Map<String, Object> mergeStructure(Map<String, Object> base, Map<String, Object> extra) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (base != null) merged.putAll(base);
        if (extra != null) {
            for (String key : List.of("title", "logline", "characters", "outline", "evaluation")) {
                Object value = extra.get(key);
                if (value != null && (!(value instanceof List<?> list) || !list.isEmpty())) {
                    merged.put(key, value);
                }
            }
        }
        return merged;
    }

    private Map<String, Object> normalizeResult(ScriptTask task, Map<String, Object> source) {
        Map<String, Object> result = source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
        copyAlias(result, "characters", "character_settings", "characterSettings", "characterProfiles", "character");
        copyAlias(result, "outline", "episode_outline", "episodeOutline", "story_outline", "episodes");
        copyAlias(result, "script_markdown", "scriptMarkdown", "screenplay", "script");
        result.put("characters", normalizeList(result.get("characters"), Integer.MAX_VALUE));
        int targetEpisodes = task.getTargetEpisodes() == null ? 1 : task.getTargetEpisodes();
        result.put("outline", normalizeList(result.get("outline"), targetEpisodes));
        Object script = result.get("script_markdown");
        if (script != null && StringUtils.hasText(String.valueOf(script))) {
            result.put("script_markdown", limitEpisodes(String.valueOf(script), targetEpisodes));
        }
        return result;
    }

    private static void copyAlias(Map<String, Object> result, String canonical, String... aliases) {
        Object current = result.get(canonical);
        if (current instanceof List<?> list && !list.isEmpty()) return;
        if (current != null && StringUtils.hasText(String.valueOf(current))) return;
        for (String alias : aliases) {
            Object value = result.get(alias);
            if (value != null) {
                result.put(canonical, value);
                return;
            }
        }
    }

    private static void applyGeneratedTitle(ScriptTask task, Map<String, Object> result) {
        String title = generatedTitle(result == null ? null : result.get("title"));
        if (title != null) task.setTitle(title);
    }

    private static String generatedTitle(Object value) {
        if (value == null) return null;
        String title = String.valueOf(value).trim();
        if (!StringUtils.hasText(title)
                || List.of("剧本生成结果", "未命名剧本", "新剧本", "灵感起草任务", "小说改编任务", "剧本评估任务")
                .contains(title)) {
            return null;
        }
        return title.length() > 256 ? title.substring(0, 256).trim() : title;
    }

    private static List<Map<String, Object>> normalizeList(Object value, int max) {
        if (!(value instanceof List<?> list)) return new ArrayList<>();
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> next = new LinkedHashMap<>();
                map.forEach((key, val) -> next.put(String.valueOf(key), val));
                normalized.add(next);
                if (normalized.size() >= max) break;
            }
        }
        return normalized;
    }

    private static void replaceNonEmptyStructure(Map<String, Object> target, Map<String, Object> source,
                                                  String... keys) {
        if (source == null) return;
        for (String key : keys) {
            Object value = source.get(key);
            if (value instanceof List<?> list && !list.isEmpty()) {
                target.put(key, value);
            } else if (value instanceof Map<?, ?> map && !map.isEmpty()) {
                target.put(key, value);
            } else if (value != null && StringUtils.hasText(String.valueOf(value))) {
                target.put(key, value);
            }
        }
    }

    private static Map<String, Object> continuationOutlineItem(Map<String, Object> generated, int episode) {
        if (generated == null) return null;
        Object direct = generated.get("outline_item");
        if (direct instanceof Map<?, ?> map) {
            Map<String, Object> item = new LinkedHashMap<>();
            map.forEach((key, value) -> item.put(String.valueOf(key), value));
            item.put("episode", episode);
            return item;
        }
        Object outline = generated.get("outline");
        if (outline instanceof List<?> list) {
            for (Object value : list) {
                if (value instanceof Map<?, ?> map && episodeValue(map.get("episode")) == episode) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    map.forEach((key, itemValue) -> item.put(String.valueOf(key), itemValue));
                    item.put("episode", episode);
                    return item;
                }
            }
            if (!list.isEmpty() && list.get(0) instanceof Map<?, ?> map) {
                Map<String, Object> item = new LinkedHashMap<>();
                map.forEach((key, value) -> item.put(String.valueOf(key), value));
                item.put("episode", episode);
                return item;
            }
        }
        return null;
    }

    private static int episodeValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return -1;
        String text = String.valueOf(value).trim();
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static int lastDocumentEpisode(String document) {
        int last = 0;
        Matcher matcher = EPISODE_MARKER.matcher(document == null ? "" : document);
        while (matcher.find()) {
            int episode = episodeNumber(matcher.group(1), matcher.group(2), matcher.group(3));
            if (episode != Integer.MAX_VALUE) last = Math.max(last, episode);
        }
        return last < 1 ? 1 : last;
    }

    private static String extractEpisodeBlock(String document, int episode) {
        if (!StringUtils.hasText(document)) return "";
        Matcher matcher = EPISODE_MARKER.matcher(document);
        int start = -1;
        int end = document.length();
        while (matcher.find()) {
            int current = episodeNumber(matcher.group(1), matcher.group(2), matcher.group(3));
            if (current == episode && start < 0) {
                start = matcher.start();
            } else if (start >= 0 && current > episode) {
                end = matcher.start();
                break;
            }
        }
        return start < 0 ? document.trim() : document.substring(start, end).trim();
    }

    private static boolean startsWithEpisodeMarker(String document, int episode) {
        Matcher matcher = EPISODE_MARKER.matcher(document == null ? "" : document);
        return matcher.find() && episodeNumber(matcher.group(1), matcher.group(2), matcher.group(3)) == episode;
    }

    private static boolean hasStructure(Map<String, Object> result) {
        return result != null
                && result.get("characters") instanceof List<?> characters && !characters.isEmpty()
                && result.get("outline") instanceof List<?> outline && !outline.isEmpty();
    }

    private static String limitEpisodes(String document, int targetEpisodes) {
        if (!StringUtils.hasText(document) || targetEpisodes < 1) return document;
        Matcher matcher = EPISODE_MARKER.matcher(document);
        while (matcher.find()) {
            int episode = episodeNumber(matcher.group(1), matcher.group(2), matcher.group(3));
            if (episode > targetEpisodes) {
                return document.substring(0, matcher.start()).trim()
                        + "\n\n【已按目标集数截取正文】";
            }
        }
        return document;
    }

    private static int episodeNumber(String digits, String chinese, String epDigits) {
        try {
            if (digits != null) return Integer.parseInt(digits);
            if (epDigits != null) return Integer.parseInt(epDigits);
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
        if (chinese == null || chinese.isBlank()) return Integer.MAX_VALUE;
        if (chinese.length() == 1 && "一二三四五六七八九".contains(chinese)) {
            return "一二三四五六七八九".indexOf(chinese) + 1;
        }
        if ("十".equals(chinese)) return 10;
        if (chinese.startsWith("十")) return 10 + "一二三四五六七八九".indexOf(chinese.substring(1)) + 1;
        if (chinese.endsWith("十")) return ("一二三四五六七八九".indexOf(chinese.substring(0, 1)) + 1) * 10;
        return Integer.MAX_VALUE;
    }

    private Map<String, Object> parseResult(String content) {
        String normalized = content == null ? "" : content.trim();
        if (normalized.startsWith("```") && normalized.endsWith("```")) {
            normalized = normalized.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        try {
            return objectMapper.readValue(normalized, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception ex) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("title", "剧本生成结果");
            fallback.put("script_markdown", content == null ? "" : content);
            fallback.put("parse_warning", "模型未返回严格 JSON，已保留原始文本");
            return fallback;
        }
    }

    private String documentFromResult(ScriptTask task, Map<String, Object> result) {
        Object script = result.get("script_markdown");
        if (script != null && StringUtils.hasText(String.valueOf(script))) {
            return String.valueOf(script);
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (Exception ignored) {
            return String.valueOf(result);
        }
    }

    private ScriptTask requireTask(Long id) {
        ScriptTask task = taskMapper.selectById(id);
        if (task == null || !UserContext.requireUserId().equals(task.getUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "剧本任务不存在");
        }
        return task;
    }

    private Long resolveModelId(Long requested) {
        if (requested != null) {
            AiModel model = modelMapper.selectById(requested);
            if (model == null || !Boolean.TRUE.equals(model.getEnabled()) || !"text".equalsIgnoreCase(model.getCategory())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "所选模型不是可用文本模型");
            }
            return requested;
        }
        AiModel model = modelMapper.selectOne(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getCategory, "text")
                .eq(AiModel::getEnabled, true)
                .orderByAsc(AiModel::getId).last("LIMIT 1"));
        return model == null ? null : model.getId();
    }

    private void saveMessage(ScriptTask task, String role, String content, String thinking) {
        ScriptMessage message = new ScriptMessage();
        message.setTaskId(task.getId());
        message.setUserId(task.getUserId());
        message.setRole(role);
        message.setContent(content == null ? "" : content);
        message.setThinkingProcess(thinking);
        message.setCreatedAt(OffsetDateTime.now());
        messageMapper.insert(message);
    }

    private void send(SseEmitter emitter, String event, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(event).data(data));
    }

    private ScriptTaskResp toResp(ScriptTask task, boolean detail) {
        ScriptTaskResp resp = new ScriptTaskResp();
        resp.setId(task.getId());
        resp.setTitle(task.getTitle());
        resp.setMode(task.getMode());
        resp.setStatus(task.getStatus());
        resp.setGenre(task.getGenre());
        resp.setTargetEpisodes(task.getTargetEpisodes());
        resp.setModelId(task.getModelId());
        resp.setModelName(llmGateway.modelName(task.getModelId()));
        resp.setInputText(task.getInputText());
        resp.setDocument(task.getDocument());
        resp.setResult(task.getResultJson());
        resp.setAttachments(task.getAttachments());
        resp.setDramaSeriesId(task.getDramaSeriesId());
        resp.setVersion(versionMapper.selectCount(new LambdaQueryWrapper<ScriptTaskVersion>()
                .eq(ScriptTaskVersion::getTaskId, task.getId())).intValue());
        resp.setCreatedAt(task.getCreatedAt());
        resp.setUpdatedAt(task.getUpdatedAt());
        if (detail) {
            List<ScriptMessage> messages = messageMapper.selectList(new LambdaQueryWrapper<ScriptMessage>()
                    .eq(ScriptMessage::getTaskId, task.getId())
                    .orderByAsc(ScriptMessage::getCreatedAt));
            resp.setMessages(messages.stream().map(this::toMessageResp).toList());
        }
        return resp;
    }

    private ScriptMessageResp toMessageResp(ScriptMessage message) {
        ScriptMessageResp resp = new ScriptMessageResp();
        resp.setId(message.getId());
        resp.setRole(message.getRole());
        resp.setContent(message.getContent());
        resp.setThinkingProcess(message.getThinkingProcess());
        resp.setCreatedAt(message.getCreatedAt());
        return resp;
    }

    private static String normalizeMode(String mode) {
        if (!List.of("idea", "adapt", "review").contains(mode)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持的剧本创作模式");
        }
        return mode;
    }

    private static String defaultTitle(String mode) {
        return switch (mode) {
            case "adapt" -> "小说改编任务";
            case "review" -> "剧本评估任务";
            default -> "灵感起草任务";
        };
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value == null ? "" : value;
        return value.substring(0, max) + "\n[内容已截断]";
    }
}
