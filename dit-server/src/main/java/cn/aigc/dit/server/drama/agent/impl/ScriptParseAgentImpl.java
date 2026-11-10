package cn.aigc.dit.server.drama.agent.impl;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.server.drama.DramaErrorText;
import cn.aigc.dit.server.drama.agent.AgentContext;
import cn.aigc.dit.server.drama.agent.AgentResult;
import cn.aigc.dit.server.drama.agent.ScriptParseAgent;
import cn.aigc.dit.server.drama.agent.ScriptParseReq;
import cn.aigc.dit.server.drama.agent.SupervisorIssue;
import cn.aigc.dit.server.drama.text.DramaTextChunker;
import cn.aigc.dit.server.drama.text.ScriptDoc;
import cn.aigc.dit.server.drama.text.ScriptParser;
import cn.aigc.dit.server.drama.text.ScriptPromptResolver;
import cn.aigc.dit.server.drama.text.TextChatGateway;
import cn.aigc.dit.server.drama.text.TextChatRequest;
import cn.aigc.dit.server.drama.text.TextChatResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * ScriptParseAgent 实现。调 LLM 把原文解析为 ScriptDoc，含 2 次重试 + supervisor 反馈注入。
 *
 * 注意：与 DramaTextServiceImpl.parseScript 并存。新链路（agent 形式化）走本类；
 * 老链路（DramaService.importSource 仍走老路径）保留用于回滚兼容。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScriptParseAgentImpl implements ScriptParseAgent {

    /** 分场生成单次请求最大 token。 */
    private static final int MAX_TOKENS = 16384;

    /** 截断后允许加倍到的上限（与 v1 老链路的 MAX_JSON_TOKENS 一致）。 */
    private static final int MAX_TOKENS_CEILING = 32768;

    /** 内部重试次数。 */
    private static final int INTERNAL_RETRIES = 2;

    /** 细拆后仍不成形时，最多再往下拆几层。 */
    private static final int MAX_SPLIT_DEPTH = 2;

    /** 锚点最多列多少条（再多只写总数，避免把提示词撑爆）。 */
    private static final int MAX_ANCHOR_LINES = 30;

    private final TextChatGateway gateway;
    private final ScriptPromptResolver promptResolver;
    private final ObjectMapper objectMapper;

    /**
     * 解析原文为 ScriptDoc。
     *
     * <p>失败阶梯与 v1 老链路（{@code DramaTextServiceImpl.attemptJson}）保持一致：
     * <ol>
     *   <li>整本一次请求；</li>
     *   <li>输出被 {@code max_tokens} 截断（finish_reason=length）→ 加倍预算原样重放；</li>
     *   <li>还是不成形 → 原文细拆成小片，逐片解析后合并。</li>
     * </ol>
     * 之前这里只有「同一个请求重发两次」，模型被截断两次就整条流水线终止，
     * 而同样的稿子走 v1 却能靠细拆拆出来 —— 差别就在这一步。
     */
    @Override
    public AgentResult<ScriptDoc> execute(AgentContext ctx, ScriptParseReq input) {
        long promptTokens = 0L;
        long completionTokens = 0L;

        int tokens = MAX_TOKENS;
        Outcome last = null;
        for (int attempt = 1; attempt <= INTERNAL_RETRIES; attempt++) {
            last = requestOnce(ctx, input, input.raw(), tokens, "attempt=" + attempt + "/" + INTERNAL_RETRIES);
            promptTokens += last.promptTokens();
            completionTokens += last.completionTokens();
            if (last.doc() != null) {
                if (attempt > 1) {
                    log.info("[script-parse-agent] 第 {} 次输出符合 schema episodeId={}", attempt, ctx.episodeId());
                }
                return AgentResult.ok(last.doc(), promptTokens, completionTokens);
            }
            if (last.truncated() && tokens < MAX_TOKENS_CEILING) {
                tokens = MAX_TOKENS_CEILING;
                log.warn("[script-parse-agent] 输出被 max_tokens 截断，加倍预算重放 episodeId={} maxTokens={}",
                        ctx.episodeId(), tokens);
            }
        }

        // 整本装不下（或模型就是不给合法 JSON）：细拆原文逐片解析后合并。
        Outcome split = parseSplit(ctx, input, input.raw(), tokens, 1);
        promptTokens += split.promptTokens();
        completionTokens += split.completionTokens();
        if (split.doc() != null) {
            return AgentResult.ok(split.doc(), promptTokens, completionTokens);
        }

        String reason = last == null ? "" : last.reason();
        return AgentResult.fail(DramaErrorText.parseFailed(INTERNAL_RETRIES, reason), INTERNAL_RETRIES);
    }

    @Override
    public ScriptParseReq withRepairFeedback(ScriptParseReq input, List<SupervisorIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return input;
        }
        StringBuilder feedback = new StringBuilder();
        feedback.append("\n\n【Supervisor 反馈，必须修复以下问题】\n");
        for (SupervisorIssue issue : issues) {
            feedback.append("- [").append(issue.severity()).append("] ")
                    .append(issue.field()).append(": ").append(issue.message());
            if (issue.suggestion() != null && !issue.suggestion().isBlank()) {
                feedback.append(" → 建议：").append(issue.suggestion());
            }
            feedback.append("\n");
        }
        // 反馈单独存：原文还要拿去切分片，混进原文只会落在最后一片上。
        return new ScriptParseReq(input.seriesId(), input.raw(), input.skeleton(), input.memoryBlock(),
                feedback.toString());
    }

    // ─────────────────────────────────────
    // 请求
    // ─────────────────────────────────────

    /**
     * 发一次请求并解析。
     *
     * @param rawText 这一片要解析的原文（整本或分片）
     * @param stage   日志里的阶段标记（attempt=1/2 或 chunk=2/3）
     */
    private Outcome requestOnce(AgentContext ctx, ScriptParseReq input, String rawText, int maxTokens,
                                String stage) {
        String system = buildSystem(input, rawText);
        String user = buildUser(rawText, input.feedback());
        TextChatRequest req = new TextChatRequest(system, user, true, maxTokens);
        log.info("[script-parse-agent] 调用模型 {} episodeId={} maxTokens={} 等待模型返回…",
                stage, ctx.episodeId(), maxTokens);
        try {
            TextChatResult resp = gateway.chat(input.seriesId(), req);
            ScriptDoc doc = parse(resp.getContent());
            boolean truncated = AgentTruncationSupport.truncatedByLength(resp.getFinishReason());
            if (doc == null) {
                log.warn("[script-parse-agent] 输出不符合 schema seriesId={} {} finishReason={} "
                                + "truncated={} contentLength={} maxTokens={} contentHead={}",
                        input.seriesId(), stage, resp.getFinishReason(), truncated,
                        resp.getContent() == null ? 0 : resp.getContent().length(), maxTokens,
                        AgentTruncationSupport.headOf(resp.getContent()));
                return Outcome.failed(truncated, AgentTruncationSupport.invalidReason(resp, truncated),
                        resp.getPromptTokens(), resp.getCompletionTokens());
            }
            return Outcome.ok(doc, resp.getPromptTokens(), resp.getCompletionTokens());
        } catch (BizException e) {
            log.warn("[script-parse-agent] LLM 业务异常 seriesId={} {} msg={}",
                    input.seriesId(), stage, e.getMessage());
            return Outcome.failed(false, DramaErrorText.modelCallFailed(e.getMessage()), 0L, 0L);
        } catch (RuntimeException e) {
            log.warn("[script-parse-agent] LLM 异常 seriesId={} {} class={} msg={}",
                    input.seriesId(), stage, e.getClass().getSimpleName(), e.getMessage());
            return Outcome.failed(false, DramaErrorText.modelCallFailed(e.getMessage()), 0L, 0L);
        }
    }

    /**
     * 原文细拆后逐片解析、合并成一份 ScriptDoc。
     *
     * <p>整本一次输出装不下时（长剧本场次多、或思考模型把 max_tokens 烧光），
     * 按场标记或段落把原文切成小片，每片单独解析再合并。某片还是不成形就再往下拆一层。
     */
    private Outcome parseSplit(AgentContext ctx, ScriptParseReq input, String rawText, int maxTokens, int depth) {
        List<String> chunks = DramaTextChunker.packRawChunksAfterTruncation(rawText);
        if (chunks.size() <= 1) {
            return Outcome.failed(false, "原文无法再切分", 0L, 0L);
        }
        log.warn("[script-parse-agent] 整本输出不成形，原文细拆重跑 episodeId={} chunks={} depth={}",
                ctx.episodeId(), chunks.size(), depth);

        List<ScriptDoc.Scene> scenes = new ArrayList<>();
        String title = null;
        String logline = null;
        String stylePrefix = null;
        long promptTokens = 0L;
        long completionTokens = 0L;
        int failed = 0;
        int index = 0;
        for (String chunk : chunks) {
            index++;
            Outcome part = parseChunk(ctx, input, chunk, maxTokens, depth, "chunk=" + index + "/" + chunks.size());
            promptTokens += part.promptTokens();
            completionTokens += part.completionTokens();
            if (part.doc() == null || part.doc().getScenes() == null || part.doc().getScenes().isEmpty()) {
                failed++;
                continue;
            }
            if (title == null) {
                title = part.doc().getTitle();
                logline = part.doc().getLogline();
                stylePrefix = part.doc().getStylePrefix();
            }
            scenes.addAll(part.doc().getScenes());
        }
        if (scenes.isEmpty()) {
            return Outcome.failed(false, "细拆后每片都没解析出来", promptTokens, completionTokens);
        }
        if (failed > 0) {
            log.warn("[script-parse-agent] 部分分片失败 episodeId={} failed={} keptScenes={}",
                    ctx.episodeId(), failed, scenes.size());
        }

        ScriptDoc merged = new ScriptDoc();
        merged.setTitle(title);
        merged.setLogline(logline);
        merged.setStylePrefix(stylePrefix);
        List<ScriptDoc.Scene> kept = new ArrayList<>();
        for (ScriptDoc.Scene scene : scenes) {
            if (scene == null) {
                continue;
            }
            int sceneIndex = kept.size() + 1;
            scene.setIndex(sceneIndex);
            scene.setSlug("scene-" + sceneIndex);
            kept.add(scene);
        }
        merged.setScenes(kept);
        return Outcome.ok(merged, promptTokens, completionTokens);
    }

    /** 单片解析：还是不成形就再拆一层（深度上限 {@link #MAX_SPLIT_DEPTH}）。 */
    private Outcome parseChunk(AgentContext ctx, ScriptParseReq input, String chunk, int maxTokens,
                               int depth, String stage) {
        Outcome outcome = requestOnce(ctx, input, chunk, maxTokens, stage);
        if (outcome.doc() != null) {
            return outcome;
        }
        if (depth < MAX_SPLIT_DEPTH) {
            return parseSplit(ctx, input, chunk, maxTokens, depth + 1);
        }
        return outcome;
    }

    // ─────────────────────────────────────
    // 提示词
    // ─────────────────────────────────────

    private String buildSystem(ScriptParseReq input, String rawText) {
        StringBuilder sb = new StringBuilder();
        sb.append(promptResolver.resolve("common")).append("\n\n");
        sb.append(promptResolver.resolve("schema_script"));
        if (input.memoryBlock() != null && !input.memoryBlock().isBlank()) {
            sb.append("\n\n").append(input.memoryBlock());
        }
        // 锚点按「这一片原文」算：细拆后每片只认自己那几个场次标题，
        // 拿整本的锚点数去要求一片，模型会拆出对不上的场次。
        List<ScriptParser.SceneMarker> anchors = ScriptParser.detectSceneMarkers(rawText);
        if (!anchors.isEmpty()) {
            sb.append("\n\n").append(renderSceneAnchors(anchors));
        }
        if (input.skeleton() != null) {
            sb.append("\n\n").append(renderSkeletonContext(input.skeleton(), !anchors.isEmpty()));
        }
        return sb.toString();
    }

    private String buildUser(String rawText, String feedback) {
        StringBuilder sb = new StringBuilder("请把以下剧本原文切分为结构化 ScriptDoc：\n\n").append(rawText);
        if (feedback != null && !feedback.isBlank()) {
            sb.append(feedback);
        }
        return sb.toString();
    }

    /**
     * 原文场次锚点：用户自己写了几个场，就是几场。
     *
     * <p>没有这一段时，场次数完全由「骨架规划（每集 ≤6 条）」说了算 ——
     * 一份 7 场的稿子会被压成三四场，还要靠 supervisor 的偏差校验再压一次。
     * 有了锚点，场次数就是硬约束，模型只负责把每一场的内容装进去。
     */
    private String renderSceneAnchors(List<ScriptParser.SceneMarker> anchors) {
        StringBuilder sb = new StringBuilder("【原文场次锚点】原文里显式写了 ")
                .append(anchors.size()).append(" 个场次标题，scenes 必须与它们一一对应：\n");
        int limit = Math.min(anchors.size(), MAX_ANCHOR_LINES);
        for (int i = 0; i < limit; i++) {
            ScriptParser.SceneMarker marker = anchors.get(i);
            sb.append("  ").append(marker.index()).append(". ")
                    .append(marker.heading().isBlank() ? "（无标题）" : marker.heading()).append("\n");
        }
        if (anchors.size() > limit) {
            sb.append("  …（其余 ").append(anchors.size() - limit).append(" 场见原文）\n");
        }
        sb.append("硬性要求：\n");
        sb.append("- 每个锚点对应且只对应一个 scene，不得把两个锚点合并成一场，也不得漏掉任何一场；\n");
        sb.append("- 锚点标题里的地名就是本场 location（只取地名，不含时间、时段与修饰词）；标题里没写地名就用正文里的地点；\n");
        sb.append("- 同一处地点出现在多个锚点里时各成一场，不要合并成一场（场景资产按地点抽取，同一处地方只留一张图）；\n");
        sb.append("- 锚点标题下面标号的是这一场里的镜头（如【分镜 N】），要落进本场的 action 与 dialogue，不要单独成场；\n");
        sb.append("- 锚点优先于故事骨架的场次规划，两者冲突时以锚点为准。\n");
        return sb.toString();
    }

    /**
     * 把 SkeletonDoc 渲染为 system 头部引导文本。仅取第一集的 scenes_hint（单集分场）。
     *
     * @param hasAnchors 原文有明确场次标记时，锚点优先，骨架的场次规划只作参考
     */
    private String renderSkeletonContext(cn.aigc.dit.server.drama.text.SkeletonDoc skeleton, boolean hasAnchors) {
        StringBuilder sb = new StringBuilder("【故事骨架引导】（以下结构仅供参考，禁止修改其中任何字段；");
        sb.append(hasAnchors ? "按原文场次锚点拆分，骨架里的场次规划只作参考" : "按其规划场景拆分");
        sb.append("）\n");
        sb.append("- 总纲：").append(nullSafe(skeleton.logline())).append("\n");
        sb.append("- 风格：").append(nullSafe(skeleton.stylePrefix())).append("\n");
        sb.append("- 跨集人物：").append(joinList(skeleton.charactersRecurrent())).append("\n");
        sb.append("- 世界观规则：").append(joinList(skeleton.worldRules())).append("\n");
        if (skeleton.episodes() != null && !skeleton.episodes().isEmpty()) {
            var ep = skeleton.episodes().get(0);
            sb.append("- 第 1 集：").append(nullSafe(ep.title())).append(" — ").append(nullSafe(ep.logline())).append("\n");
            if (ep.scenesHint() != null) {
                sb.append("  · 计划场景：\n");
                for (var hint : ep.scenesHint()) {
                    sb.append("    - ").append(nullSafe(hint.heading()))
                      .append("（").append(nullSafe(hint.purpose())).append("）\n");
                }
            }
            sb.append("  · 高潮：").append(nullSafe(ep.climax())).append("\n");
        }
        return sb.toString();
    }

    // ─────────────────────────────────────
    // 解析与工具
    // ─────────────────────────────────────

    private ScriptDoc parse(String content) {
        return AgentTruncationSupport.parseJson(objectMapper, content, ScriptDoc.class);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static String joinList(List<String> list) {
        if (list == null || list.isEmpty()) return "";
        return String.join("、", list);
    }

    /** 一次请求的结果。 */
    private record Outcome(ScriptDoc doc, boolean truncated, String reason,
                           long promptTokens, long completionTokens) {
        static Outcome ok(ScriptDoc doc, long promptTokens, long completionTokens) {
            return new Outcome(doc, false, null, promptTokens, completionTokens);
        }

        static Outcome failed(boolean truncated, String reason, long promptTokens, long completionTokens) {
            return new Outcome(null, truncated, reason, promptTokens, completionTokens);
        }
    }
}
