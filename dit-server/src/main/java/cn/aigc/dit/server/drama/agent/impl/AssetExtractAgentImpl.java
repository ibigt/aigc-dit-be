package cn.aigc.dit.server.drama.agent.impl;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.server.drama.DramaAssetDrafts;
import cn.aigc.dit.server.drama.DramaErrorText;
import cn.aigc.dit.server.drama.agent.AgentContext;
import cn.aigc.dit.server.drama.agent.AgentResult;
import cn.aigc.dit.server.drama.agent.ScriptAgent;
import cn.aigc.dit.server.drama.agent.AssetExtractAgent;
import cn.aigc.dit.server.drama.agent.AssetExtractReq;
import cn.aigc.dit.server.drama.agent.SupervisorIssue;
import cn.aigc.dit.server.drama.agent.SupervisorAgent;
import cn.aigc.dit.server.drama.agent.SupervisorVerdict;
import cn.aigc.dit.server.drama.text.AssetDraftList;
import cn.aigc.dit.server.drama.text.DramaTextChunker;
import cn.aigc.dit.server.drama.text.ScriptDoc;
import cn.aigc.dit.server.drama.text.ScriptPromptResolver;
import cn.aigc.dit.server.drama.text.TextChatGateway;
import cn.aigc.dit.server.drama.text.TextChatRequest;
import cn.aigc.dit.server.drama.text.TextChatResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * AssetExtractAgent 实现。调 LLM 从 ScriptDoc 抽取资产清单。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssetExtractAgentImpl implements AssetExtractAgent {

    /** 资产抽取单次请求最大 token。 */
    private static final int MAX_TOKENS = 8192;

    /** 截断后允许加倍到的上限（与 v1 老链路的 MAX_JSON_TOKENS 一致）。 */
    private static final int MAX_TOKENS_CEILING = AgentTruncationSupport.MAX_TOKENS_CEILING;

    /** 内部重试次数。 */
    private static final int INTERNAL_RETRIES = 2;

    /** 拆片后仍不成形时，最多再往下拆几层。 */
    private static final int MAX_SPLIT_DEPTH = 2;

    /** 拆片抽取的并发度（与 v1 老链路的 ASSET_ENRICHMENT_CONCURRENCY 一致）。 */
    private static final int MAX_SPLIT_CONCURRENCY = 2;

    private final TextChatGateway gateway;
    private final ScriptPromptResolver promptResolver;
    private final ObjectMapper objectMapper;

    /**
     * 抽取资产清单。
     *
     * <p>失败阶梯与剧本步（{@code ScriptParseAgentImpl}）、v1 老链路保持一致：
     * <ol>
     *   <li>整集一次请求；</li>
     *   <li>输出被 {@code max_tokens} 截断（finish_reason=length）→ 加倍预算原样重放；</li>
     *   <li>还是不成形 → 按场次拆片，逐片抽取后合并。</li>
     * </ol>
     * 之前这里只有「同一个请求重发两次」，模型被截断两次就整条流水线终止。
     */
    @Override
    public AgentResult<AssetDraftList> execute(AgentContext ctx, AssetExtractReq input) {
        if (DramaAssetDrafts.canonicalSkeleton(input.script()).isEmpty()) {
            log.warn("[asset-agent] 剧本没有可抽取的人物、场景或道具 episodeId={}", ctx.episodeId());
            return AgentResult.fail("剧本里没有可抽取的人物和场景");
        }
        long promptTokens = 0L;
        long completionTokens = 0L;

        int tokens = MAX_TOKENS;
        Outcome last = null;
        for (int attempt = 1; attempt <= INTERNAL_RETRIES; attempt++) {
            last = requestOnce(ctx, input, tokens, "attempt=" + attempt + "/" + INTERNAL_RETRIES);
            promptTokens += last.promptTokens();
            completionTokens += last.completionTokens();
            if (last.assets() != null) {
                if (attempt > 1) {
                    log.info("[asset-agent] 第 {} 次输出符合 schema episodeId={}", attempt, ctx.episodeId());
                }
                return AgentResult.ok(last.assets(), promptTokens, completionTokens);
            }
            if (last.truncated() && tokens < MAX_TOKENS_CEILING) {
                tokens = MAX_TOKENS_CEILING;
                log.warn("[asset-agent] 输出被 max_tokens 截断，加倍预算重放 episodeId={} maxTokens={}",
                        ctx.episodeId(), tokens);
            }
        }

        // 整集装不下（或模型就是不给合法 JSON）：按场次拆片，逐片抽取后合并。
        Outcome split = extractSplit(ctx, input, input.script(), tokens, 1);
        promptTokens += split.promptTokens();
        completionTokens += split.completionTokens();
        if (split.assets() != null) {
            return AgentResult.ok(split.assets(), promptTokens, completionTokens);
        }

        String reason = last == null ? "" : last.reason();
        return AgentResult.fail(DramaErrorText.parseFailed(INTERNAL_RETRIES, reason)
                + "；拆成小片重跑也没抽出资产（" + split.reason() + "）", INTERNAL_RETRIES);
    }

    /** Asset enrichment must not accept a second response that reintroduces aliases. */
    @Override
    public AgentResult<AssetDraftList> executeWithSupervision(
            AgentContext ctx, AssetExtractReq input, SupervisorAgent<AssetDraftList> supervisor) {
        AgentResult<AssetDraftList> first = execute(ctx, input);
        if (!first.success() || supervisor == null) return first;
        SupervisorVerdict firstVerdict = supervisor.validate(ctx, first.output(), input);
        if (firstVerdict.passed()) {
            log.info("[asset-supervisor] 校验通过 episodeId={}", ctx.episodeId());
            return first;
        }
        // 只有 WARNING：不重跑。描述写得糙不是「不合格」——模型整篇重写一遍更慢更贵，
        // 而资产页的空白字段有 ensurePrompts 兜底。把整集分析判死不划算（2026-11-03 的线上故障
        // 就是这么来的：50 条描述质量问题把一条能跑完的流水线判成了失败）。
        if (!firstVerdict.hasErrors()) {
            log.warn("[asset-supervisor] 校验有提醒（不阻塞，不重跑）episodeId={} 问题数={}",
                    ctx.episodeId(), firstVerdict.issues().size());
            ScriptAgent.logIssues("asset", ctx.episodeId(), firstVerdict.issues());
            return first.withSupervisorIssues(firstVerdict.issues());
        }
        log.warn("[asset-supervisor] 校验不通过 episodeId={} 问题数={}，准备注入反馈修复重跑一次",
                ctx.episodeId(), firstVerdict.issues().size());
        ScriptAgent.logIssues("asset", ctx.episodeId(), firstVerdict.issues());
        AssetExtractReq repairedInput = withRepairFeedback(input, firstVerdict.issues());
        AgentResult<AssetDraftList> repaired = execute(ctx, repairedInput);
        long promptTokens = first.promptTokens() + repaired.promptTokens();
        long completionTokens = first.completionTokens() + repaired.completionTokens();
        if (!repaired.success()) {
            return new AgentResult<>(false, null, repaired.error(), true, firstVerdict.issues(),
                    first.retryCount() + repaired.retryCount(), 1, promptTokens, completionTokens);
        }
        SupervisorVerdict repairedVerdict = supervisor.validate(ctx, repaired.output(), input);
        if (!repairedVerdict.hasErrors()) {
            log.info("[asset-supervisor] 修复后通过 episodeId={}", ctx.episodeId());
            return new AgentResult<>(true, repaired.output(), null, true, firstVerdict.issues(),
                    first.retryCount() + repaired.retryCount(), 1, promptTokens, completionTokens);
        }
        log.warn("[asset-supervisor] 修复后仍不通过 episodeId={} 问题数={}",
                ctx.episodeId(), repairedVerdict.issues().size());
        ScriptAgent.logIssues("asset", ctx.episodeId(), repairedVerdict.issues());
        return new AgentResult<>(false, null, "按反馈改写了一次，资产清单仍和剧本对不上（名称或类型不一致）",
                true, repairedVerdict.issues(), first.retryCount() + repaired.retryCount(), 1,
                promptTokens, completionTokens);
    }

    @Override
    public AssetExtractReq withRepairFeedback(AssetExtractReq input, List<SupervisorIssue> issues) {
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
        // 反馈单独存：拆片抽取时每一片都要带上它，混进道具汇总里模型会当成道具名，
        // 抽出一堆规范词表里没有的资产。
        return new AssetExtractReq(input.seriesId(), input.episodeId(), input.script(),
                input.propsSummary(), input.memoryBlock(), feedback.toString());
    }

    // ─────────────────────────────────────
    // 请求
    // ─────────────────────────────────────

    /** 发一次请求并解析。 */
    private Outcome requestOnce(AgentContext ctx, AssetExtractReq input, int maxTokens, String stage) {
        String system = buildSystem(input.memoryBlock());
        String user = buildUser(input);
        TextChatRequest req = new TextChatRequest(system, user, true, maxTokens);
        log.info("[asset-agent] 调用模型 {} episodeId={} maxTokens={} 等待模型返回…",
                stage, ctx.episodeId(), maxTokens);
        try {
            TextChatResult resp = gateway.chat(input.seriesId(), req);
            AssetDraftList assets = parse(resp.getContent());
            boolean truncated = AgentTruncationSupport.truncatedByLength(resp.getFinishReason());
            if (assets == null) {
                log.warn("[asset-agent] 输出不符合 schema seriesId={} {} finishReason={} truncated={} "
                                + "contentLength={} maxTokens={} contentHead={}",
                        input.seriesId(), stage, resp.getFinishReason(), truncated,
                        resp.getContent() == null ? 0 : resp.getContent().length(), maxTokens,
                        AgentTruncationSupport.headOf(resp.getContent()));
                return Outcome.failed(truncated, AgentTruncationSupport.invalidReason(resp, truncated),
                        resp.getPromptTokens(), resp.getCompletionTokens());
            }
            // 模型的名字与类型不可信：一律投影回规范骨架，模型只贡献描述字段。
            assets = projectOntoSkeleton(input.script(), assets);
            return Outcome.ok(assets, resp.getPromptTokens(), resp.getCompletionTokens());
        } catch (BizException e) {
            log.warn("[asset-agent] LLM 业务异常 seriesId={} {} msg={}",
                    input.seriesId(), stage, e.getMessage());
            return Outcome.failed(false, DramaErrorText.modelCallFailed(e.getMessage()), 0L, 0L);
        } catch (RuntimeException e) {
            log.warn("[asset-agent] LLM 异常 seriesId={} {} class={} msg={}",
                    input.seriesId(), stage, e.getClass().getSimpleName(), e.getMessage());
            return Outcome.failed(false, DramaErrorText.modelCallFailed(e.getMessage()), 0L, 0L);
        }
    }

    /**
     * 按场次拆片后逐片抽取、合并成一份资产清单。
     *
     * <p>整集一次抽不完时（资产条目多、或思考模型把 max_tokens 烧光），按场次分组
     * 分别抽，每片只带自己的场次与道具，最后按「类型 + 名字」去重合并。
     *
     * <p>各片之间互不依赖（每片自带剧本与道具），所以并发跑（并发度 {@link #MAX_SPLIT_CONCURRENCY}，
     * 与 v1 一致）：片数多时能省掉一半等待。
     */
    private Outcome extractSplit(AgentContext ctx, AssetExtractReq input, ScriptDoc script,
                                 int maxTokens, int depth) {
        List<List<ScriptDoc.Scene>> packs = DramaTextChunker.packScenes(script.getScenes());
        if (packs.size() <= 1) {
            return Outcome.failed(false, "剧本无法再切分", 0L, 0L);
        }
        int workers = Math.min(MAX_SPLIT_CONCURRENCY, packs.size());
        log.warn("[asset-agent] 整本输出不成形，按场次拆片重跑 episodeId={} packs={} depth={} concurrency={}",
                ctx.episodeId(), packs.size(), depth, workers);

        // 每片写各自的槽位：并发下完成顺序不定，合并必须按片序，去重结果才稳定。
        PackResult[] parts = new PackResult[packs.size()];
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<?>> futures = new ArrayList<>(packs.size());
            for (int i = 0; i < packs.size(); i++) {
                final int index = i;
                final List<ScriptDoc.Scene> pack = packs.get(i);
                futures.add(pool.submit(() -> {
                    try {
                        parts[index] = extractPack(ctx, input, pack, maxTokens, depth,
                                "pack=" + (index + 1) + "/" + packs.size());
                    } catch (RuntimeException e) {
                        log.warn("[asset-agent] 分片异常已跳过 episodeId={} pack={}/{} msg={}",
                                ctx.episodeId(), index + 1, packs.size(), e.getMessage());
                        parts[index] = PackResult.empty();
                    }
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("[asset-agent] 资产并发被中断 episodeId={}", ctx.episodeId());
                    break;
                } catch (ExecutionException e) {
                    log.warn("[asset-agent] 资产并发任务异常 episodeId={} msg={}", ctx.episodeId(),
                            e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
                }
            }
        } finally {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(30, TimeUnit.MINUTES)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // 按片序合并去重：同一个角色会跨片出现，保留信息更全的那份。
        Map<String, AssetDraftList.AssetDraft> merged = new LinkedHashMap<>();
        long promptTokens = 0L;
        long completionTokens = 0L;
        int failedPacks = 0;
        for (PackResult part : parts) {
            if (part == null) {
                failedPacks++;
                continue;
            }
            promptTokens += part.promptTokens();
            completionTokens += part.completionTokens();
            failedPacks += part.failedPacks();
            for (AssetDraftList.AssetDraft draft : part.assets()) {
                mergeDraft(merged, draft);
            }
        }
        if (merged.isEmpty()) {
            return Outcome.failed(false, "拆片后每片都没抽出来", promptTokens, completionTokens);
        }
        if (failedPacks > 0) {
            log.warn("[asset-agent] 部分分片失败 episodeId={} failed={} keptAssets={}",
                    ctx.episodeId(), failedPacks, merged.size());
        }

        AssetDraftList result = new AssetDraftList();
        result.setAssets(new ArrayList<>(merged.values()));
        return Outcome.ok(result, promptTokens, completionTokens);
    }

    /** 单片抽取：还是不成形就把这片再对半分（深度上限 {@link #MAX_SPLIT_DEPTH}）。 */
    private PackResult extractPack(AgentContext ctx, AssetExtractReq input, List<ScriptDoc.Scene> pack,
                                   int maxTokens, int depth, String stage) {
        Outcome outcome = requestOnce(ctx, chunkRequest(input, pack), maxTokens, stage);
        if (outcome.assets() != null && outcome.assets().getAssets() != null) {
            return new PackResult(outcome.assets().getAssets(), outcome.promptTokens(),
                    outcome.completionTokens(), 0);
        }
        if (depth < MAX_SPLIT_DEPTH && pack.size() > 1) {
            int mid = Math.max(1, pack.size() / 2);
            log.warn("[asset-agent] 分片仍不成形，对半再拆 episodeId={} {} scenes={} -> {}+{}",
                    ctx.episodeId(), stage, pack.size(), mid, pack.size() - mid);
            PackResult left = extractPack(ctx, input, pack.subList(0, mid), maxTokens, depth + 1, stage + ".a");
            PackResult right = extractPack(ctx, input, pack.subList(mid, pack.size()),
                    maxTokens, depth + 1, stage + ".b");
            return PackResult.merge(left, right);
        }
        return new PackResult(List.of(), outcome.promptTokens(), outcome.completionTokens(), 1);
    }

    /** 一个分片对应的请求：只带这几场的剧本、道具与规范骨架。 */
    private AssetExtractReq chunkRequest(AssetExtractReq input, List<ScriptDoc.Scene> pack) {
        ScriptDoc chunk = scriptChunk(input.script(), pack);
        return new AssetExtractReq(input.seriesId(), input.episodeId(), chunk,
                extractPropsSummary(chunk), input.memoryBlock(), input.feedback());
    }

    /** 只保留这几场。资产输出不含场次号，场次号保持原样即可。 */
    private static ScriptDoc scriptChunk(ScriptDoc script, List<ScriptDoc.Scene> pack) {
        ScriptDoc chunk = new ScriptDoc();
        if (script != null) {
            chunk.setTitle(script.getTitle());
            chunk.setLogline(script.getLogline());
            chunk.setStylePrefix(script.getStylePrefix());
        }
        chunk.setScenes(new ArrayList<>(pack));
        return chunk;
    }

    /**
     * 合并一条资产。
     *
     * <p>同一个角色会在多个分片里各出现一次，按「类型 + 名字 + 状态标签」去重；保留信息更全的那份，
     * 别让后一片的空字段把前一片填好的描述盖掉。
     *
     * <p>状态标签必须进键：同一个角色的本体与入梦版本名字相同（族名），只差状态标签，
     * 按名字去重会把版本吞掉。
     */
    private static void mergeDraft(Map<String, AssetDraftList.AssetDraft> merged,
                                   AssetDraftList.AssetDraft draft) {
        if (draft == null || draft.getName() == null || draft.getName().isBlank()) {
            return;
        }
        String key = (draft.getType() == null ? "" : draft.getType()) + "\u0000"
                + draft.getName() + "\u0000" + stateTagOf(draft);
        AssetDraftList.AssetDraft kept = merged.get(key);
        if (kept == null) {
            merged.put(key, draft);
            return;
        }
        if (isBlank(kept.getDescription()) && !isBlank(draft.getDescription())) {
            kept.setDescription(draft.getDescription());
        }
        if (isBlank(kept.getIdentityInvariants()) && !isBlank(draft.getIdentityInvariants())) {
            kept.setIdentityInvariants(draft.getIdentityInvariants());
        }
        if (isBlank(kept.getVoiceLock()) && !isBlank(draft.getVoiceLock())) {
            kept.setVoiceLock(draft.getVoiceLock());
        }
    }

    /** 状态标签缺失时按 base 算，避免本体与版本被混成一条。 */
    private static String stateTagOf(AssetDraftList.AssetDraft draft) {
        String tag = draft == null ? null : draft.getStateTag();
        return tag == null || tag.isBlank() ? "base" : tag.strip();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 把模型输出投影回规范骨架：名字、类型、状态标签由代码决定，模型只贡献描述字段。
     *
     * <p>模型爱改名（「拖鞋」→「大号塑料拖鞋」）、爱改类型（把道具写成角色）、爱多抽，
     * 词表校验会把这些统统判死，整条流水线在资产步终止（2026-11-03 在 14 环境实测：
     * 一集剧本抽出 50 条词表外资产，修复轮只让模型删条目，仍不通过）。
     *
     * <p>这里把模型的输出按名字挂回骨架：挂得上的取它的描述，挂不上的用保底描述，
     * 模型自己发明的条目直接丢弃 —— 资产清单永远等于规范骨架，描述质量只影响提示不拦流程。
     */
    static AssetDraftList projectOntoSkeleton(ScriptDoc script, AssetDraftList modelOutput) {
        List<DramaAssetDrafts.CanonicalAsset> skeleton = DramaAssetDrafts.canonicalSkeleton(script);
        if (skeleton.isEmpty()) {
            // 没有骨架可投影（剧本里没有角色/场景/道具）：原样返回，交给 supervisor 判定。
            return modelOutput;
        }
        List<AssetDraftList.AssetDraft> modelAssets = modelOutput == null || modelOutput.getAssets() == null
                ? List.of()
                : modelOutput.getAssets();
        Map<AssetKey, AssetDraftList.AssetDraft> byTypeAndName = new LinkedHashMap<>();
        Map<String, AssetDraftList.AssetDraft> byName = new LinkedHashMap<>();
        // 归一后的「类型 + 族名 + 状态标签」：同一个角色的本体与入梦版本名字相同（族名），
        // 只差状态标签，按名字匹配会把两个版本配错。
        Map<String, AssetDraftList.AssetDraft> byCanonical = new LinkedHashMap<>();
        for (AssetDraftList.AssetDraft draft : modelAssets) {
            if (draft == null || isBlank(draft.getName())) {
                continue;
            }
            String name = draft.getName().strip();
            byName.putIfAbsent(name, draft);
            if (!isBlank(draft.getType())) {
                String type = draft.getType().strip();
                byTypeAndName.putIfAbsent(new AssetKey(type, name), draft);
                DramaAssetDrafts.CanonicalName canonical = DramaAssetDrafts.canonicalName(name, type);
                byCanonical.putIfAbsent(canonicalKey(type, canonical.name(), canonical.stateTag()), draft);
            }
        }
        Set<AssetDraftList.AssetDraft> used = Collections.newSetFromMap(new IdentityHashMap<>());

        List<AssetDraftList.AssetDraft> projected = new ArrayList<>(skeleton.size());
        for (DramaAssetDrafts.CanonicalAsset canonical : skeleton) {
            AssetDraftList.AssetDraft target = new AssetDraftList.AssetDraft();
            target.setName(canonical.name());
            target.setType(canonical.type());
            target.setStateTag(canonical.stateTag());
            target.setMustSplit(canonical.mustSplit());
            target.setDescription(DramaAssetDrafts.fallbackDescription(canonical.type(), canonical.name()));

            AssetDraftList.AssetDraft matched = matchModelDraft(canonical, byCanonical, byTypeAndName, byName,
                    modelAssets, used);
            if (matched != null) {
                used.add(matched);
                DramaAssetDrafts.mergeEnrichmentFields(target, matched);
            }
            projected.add(target);
        }
        AssetDraftList result = new AssetDraftList();
        result.setAssets(projected);
        return result;
    }

    /**
     * 给骨架条目找模型写的那一条：类型与族名与状态标签全对 → 类型与名字全对 →
     * 名字对（类型不对也要）→ 名字互相包含（模型加了前缀）。
     */
    private static AssetDraftList.AssetDraft matchModelDraft(
            DramaAssetDrafts.CanonicalAsset canonical,
            Map<String, AssetDraftList.AssetDraft> byCanonical,
            Map<AssetKey, AssetDraftList.AssetDraft> byTypeAndName,
            Map<String, AssetDraftList.AssetDraft> byName,
            List<AssetDraftList.AssetDraft> modelAssets,
            Set<AssetDraftList.AssetDraft> used) {
        AssetDraftList.AssetDraft canonicalHit = byCanonical.get(
                canonicalKey(canonical.type(), canonical.name(), canonical.stateTag()));
        if (canonicalHit != null && !used.contains(canonicalHit)) {
            return canonicalHit;
        }
        AssetDraftList.AssetDraft exact = byTypeAndName.get(new AssetKey(canonical.type(), canonical.name()));
        if (exact != null && !used.contains(exact)) {
            return exact;
        }
        AssetDraftList.AssetDraft sameName = byName.get(canonical.name());
        if (sameName != null && !used.contains(sameName)) {
            return sameName;
        }
        AssetDraftList.AssetDraft best = null;
        for (AssetDraftList.AssetDraft draft : modelAssets) {
            if (draft == null || isBlank(draft.getName()) || used.contains(draft)) {
                continue;
            }
            String candidate = draft.getName().strip();
            if (!candidate.contains(canonical.name()) && !canonical.name().contains(candidate)) {
                continue;
            }
            // 取最接近的一条：模型写「大号塑料拖鞋」时，骨架里的「拖鞋」用它的描述。
            if (best == null || candidate.length() < best.getName().strip().length()) {
                best = draft;
            }
        }
        return best;
    }

    /** 「类型 + 名字」键：模型条目与骨架条目的匹配用。 */
    private record AssetKey(String type, String name) {
    }

    /** 「类型 + 族名 + 状态标签」键：版本与本体的区分靠它。 */
    private static String canonicalKey(String type, String name, String stateTag) {
        return type + "\u0000" + name + "\u0000"
                + (stateTag == null || stateTag.isBlank() ? "base" : stateTag.strip());
    }

    // ─────────────────────────────────────
    // 提示词
    // ─────────────────────────────────────

    private String buildSystem(String memoryBlock) {
        StringBuilder sb = new StringBuilder();
        sb.append(promptResolver.resolve("common")).append("\n\n");
        sb.append(promptResolver.resolve("schema_assets"));
        if (memoryBlock != null && !memoryBlock.isBlank()) {
            sb.append("\n\n").append(memoryBlock);
        }
        return sb.toString();
    }

    private String buildUser(AssetExtractReq input) {
        StringBuilder sb = new StringBuilder();
        sb.append("请按下面给出的规范资产骨架逐条填写描述字段。\n")
                .append("assets 的条数与顺序必须与骨架完全一致；每条的 name 与 type 必须逐字照抄骨架，")
                .append("禁止新增、删除、改名、改类型，也禁止拆出骨架里没有的「本体名·入梦」这类条目。\n")
                .append("只填 description、identity_invariants、voice_lock 三个字段；")
                .append("state_tag 与 must_split 原样照抄，image_prompt 一律输出空字符串。\n")
                .append("骨架里同名但 state_tag 不同的条目，是同一个角色的不同状态版本"
                        + "（本体 / 入梦 / 换装），名字就是同一个，要分别写，只写该状态下的外形与设定。\n\n");
        sb.append("【规范资产骨架（逐条原样返回，只填三个描述字段）】\n");
        sb.append(renderSkeleton(input.script()));
        sb.append("\n\n【ScriptDoc（只作参考，资产名以骨架为准）】\n");
        sb.append(renderScript(input.script()));
        if (input.feedback() != null && !input.feedback().isBlank()) {
            sb.append(input.feedback());
        }
        return sb.toString();
    }

    /** 骨架模板：每条带空的描述字段，模型照着填。 */
    private String renderSkeleton(ScriptDoc script) {
        List<AssetDraftList.AssetDraft> rows = new ArrayList<>();
        for (DramaAssetDrafts.CanonicalAsset canonical : DramaAssetDrafts.canonicalSkeleton(script)) {
            AssetDraftList.AssetDraft row = new AssetDraftList.AssetDraft();
            row.setName(canonical.name());
            row.setType(canonical.type());
            row.setStateTag(canonical.stateTag());
            row.setMustSplit(canonical.mustSplit());
            row.setDescription("");
            row.setIdentityInvariants("");
            row.setImagePrompt("");
            row.setVoiceLock("");
            rows.add(row);
        }
        try {
            return objectMapper.writeValueAsString(rows);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private String renderScript(ScriptDoc script) {
        try {
            return objectMapper.writeValueAsString(script);
        } catch (JsonProcessingException e) {
            return "(序列化失败)";
        }
    }

    private AssetDraftList parse(String content) {
        AssetDraftList list = AgentTruncationSupport.parseJson(objectMapper, content, AssetDraftList.class);
        if (list != null && list.getAssets() != null && !list.getAssets().isEmpty()) {
            return list;
        }
        // 整份 JSON 解析不出来时，assets 数组本身可能还是好的，单独捞出来重拼一次
        //（v1 老链路的同一招）。
        String array = AgentTruncationSupport.extractJsonArray(content, "assets");
        if (array == null) {
            return list;
        }
        AssetDraftList salvaged = AgentTruncationSupport.parseJson(objectMapper,
                "{\"assets\":" + array + "}", AssetDraftList.class);
        if (salvaged != null && salvaged.getAssets() != null && !salvaged.getAssets().isEmpty()) {
            log.info("[asset-agent] 资产经 assets 数组抢救解析成功 count={}", salvaged.getAssets().size());
            return salvaged;
        }
        return list;
    }

    /**
     * 工具方法：从 ScriptDoc 中按去重顺序提取所有场景道具。
     */
    public static List<String> extractPropsSummary(ScriptDoc script) {
        if (script == null || script.getScenes() == null) return List.of();
        Set<String> props = new LinkedHashSet<>();
        for (ScriptDoc.Scene s : script.getScenes()) {
            if (s.getProps() != null) {
                props.addAll(s.getProps());
            }
        }
        return props.stream().collect(Collectors.toList());
    }

    /**
     * 一片抽取的结果。
     *
     * <p>每片自己一份，并发跑完后再按片序合并 —— 所以这里不需要共享计数器，
     * token 也不会因为并发而丢。
     */
    private record PackResult(List<AssetDraftList.AssetDraft> assets,
                              long promptTokens, long completionTokens, int failedPacks) {
        static PackResult empty() {
            return new PackResult(List.of(), 0L, 0L, 1);
        }

        /** 左右两半拼回一片：顺序必须保持左片在前（场次靠前的先）。 */
        static PackResult merge(PackResult left, PackResult right) {
            List<AssetDraftList.AssetDraft> assets = new ArrayList<>(left.assets());
            assets.addAll(right.assets());
            return new PackResult(assets,
                    left.promptTokens() + right.promptTokens(),
                    left.completionTokens() + right.completionTokens(),
                    left.failedPacks() + right.failedPacks());
        }
    }

    /** 一次请求的结果。 */
    private record Outcome(AssetDraftList assets, boolean truncated, String reason,
                           long promptTokens, long completionTokens) {
        static Outcome ok(AssetDraftList assets, long promptTokens, long completionTokens) {
            return new Outcome(assets, false, null, promptTokens, completionTokens);
        }

        static Outcome failed(boolean truncated, String reason, long promptTokens, long completionTokens) {
            return new Outcome(null, truncated, reason, promptTokens, completionTokens);
        }
    }
}
