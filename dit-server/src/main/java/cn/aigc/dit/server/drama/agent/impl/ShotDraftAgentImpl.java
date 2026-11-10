package cn.aigc.dit.server.drama.agent.impl;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.server.drama.DramaAssetDrafts;
import cn.aigc.dit.server.drama.DramaErrorText;
import cn.aigc.dit.server.drama.agent.AgentContext;
import cn.aigc.dit.server.drama.agent.AgentResult;
import cn.aigc.dit.server.drama.agent.ShotDraftAgent;
import cn.aigc.dit.server.drama.agent.ShotDraftReq;
import cn.aigc.dit.server.drama.agent.SupervisorIssue;
import cn.aigc.dit.server.drama.text.AssetDraftList;
import cn.aigc.dit.server.drama.text.DramaTextChunker;
import cn.aigc.dit.server.drama.text.ScriptDoc;
import cn.aigc.dit.server.drama.text.ScriptPromptResolver;
import cn.aigc.dit.server.drama.text.ShotDraftList;
import cn.aigc.dit.server.drama.text.TextChatGateway;
import cn.aigc.dit.server.drama.text.TextChatRequest;
import cn.aigc.dit.server.drama.text.TextChatResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * ShotDraftAgent 实现。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShotDraftAgentImpl implements ShotDraftAgent {

    /** 分镜草稿单次请求最大 token（分镜输出可能很大）。 */
    private static final int MAX_TOKENS = 16384;

    /** 截断后允许加倍到的上限（与 v1 老链路的 MAX_JSON_TOKENS 一致）。 */
    private static final int MAX_TOKENS_CEILING = AgentTruncationSupport.MAX_TOKENS_CEILING;

    /** 内部重试次数。 */
    private static final int INTERNAL_RETRIES = 2;

    /** 分片后仍不成形时，最多再往下拆几层。 */
    private static final int MAX_SPLIT_DEPTH = 2;

    /** 拆片出镜的并发度（与 v1 老链路的 SHOT_DRAFT_CONCURRENCY 一致）。 */
    private static final int MAX_SPLIT_CONCURRENCY = 2;

    private final TextChatGateway gateway;
    private final ScriptPromptResolver promptResolver;
    private final ObjectMapper objectMapper;

    /**
     * 生成分镜草稿。
     *
     * <p>失败阶梯与剧本步（{@code ScriptParseAgentImpl}）、v1 老链路保持一致：
     * <ol>
     *   <li>整集一次请求；</li>
     *   <li>输出被 {@code max_tokens} 截断（finish_reason=length）→ 加倍预算原样重放；</li>
     *   <li>还是不成形 → 按场次拆片，逐片出镜后合并。</li>
     * </ol>
     * 分镜是整集里最大的 JSON（每镜还有 beats），被截断的概率最高 —— 之前这里只是把
     * 同一个请求原样重发两次，预算没变，模型必然再被截断一次，整条流水线就此终止。
     */
    @Override
    public AgentResult<ShotDraftList> execute(AgentContext ctx, ShotDraftReq input) {
        long promptTokens = 0L;
        long completionTokens = 0L;

        int tokens = MAX_TOKENS;
        Outcome last = null;
        for (int attempt = 1; attempt <= INTERNAL_RETRIES; attempt++) {
            last = requestOnce(ctx, input, tokens, "attempt=" + attempt + "/" + INTERNAL_RETRIES);
            promptTokens += last.promptTokens();
            completionTokens += last.completionTokens();
            if (last.shots() != null) {
                if (attempt > 1) {
                    log.info("[shot-agent] 第 {} 次输出符合 schema episodeId={}", attempt, ctx.episodeId());
                }
                return AgentResult.ok(last.shots(), promptTokens, completionTokens);
            }
            if (last.truncated() && tokens < MAX_TOKENS_CEILING) {
                tokens = MAX_TOKENS_CEILING;
                log.warn("[shot-agent] 输出被 max_tokens 截断，加倍预算重放 episodeId={} maxTokens={}",
                        ctx.episodeId(), tokens);
            }
        }

        // 整集装不下：按场次拆片，逐片出镜后合并。
        Outcome split = draftSplit(ctx, input, input.script(), tokens, 1);
        promptTokens += split.promptTokens();
        completionTokens += split.completionTokens();
        if (split.shots() != null) {
            return AgentResult.ok(split.shots(), promptTokens, completionTokens);
        }

        String reason = last == null ? "" : last.reason();
        return AgentResult.fail(DramaErrorText.parseFailed(INTERNAL_RETRIES, reason)
                + "；拆成小片重跑也没出分镜（" + split.reason() + "）", INTERNAL_RETRIES);
    }

    @Override
    public ShotDraftReq withRepairFeedback(ShotDraftReq input, List<SupervisorIssue> issues) {
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
        // 反馈单独存：拆片重跑时每一片都要带上它，混进项目记忆里会被当成设定。
        return new ShotDraftReq(input.seriesId(), input.episodeId(), input.script(), input.assets(),
                input.memoryBlock(), feedback.toString());
    }

    // ─────────────────────────────────────
    // 请求
    // ─────────────────────────────────────

    /** 发一次请求并解析。 */
    private Outcome requestOnce(AgentContext ctx, ShotDraftReq input, int maxTokens, String stage) {
        String system = buildSystem(input.memoryBlock());
        String user = buildUser(input);
        TextChatRequest req = new TextChatRequest(system, user, true, maxTokens);
        log.info("[shot-agent] 调用模型 {} episodeId={} maxTokens={} 等待模型返回…",
                stage, ctx.episodeId(), maxTokens);
        try {
            TextChatResult resp = gateway.chat(input.seriesId(), req);
            ShotDraftList shots = parse(resp.getContent());
            boolean truncated = AgentTruncationSupport.truncatedByLength(resp.getFinishReason());
            if (shots == null) {
                log.warn("[shot-agent] 输出不符合 schema seriesId={} {} finishReason={} truncated={} "
                                + "contentLength={} maxTokens={} contentHead={}",
                        input.seriesId(), stage, resp.getFinishReason(), truncated,
                        resp.getContent() == null ? 0 : resp.getContent().length(), maxTokens,
                        AgentTruncationSupport.headOf(resp.getContent()));
                return Outcome.failed(truncated, AgentTruncationSupport.invalidReason(resp, truncated),
                        resp.getPromptTokens(), resp.getCompletionTokens());
            }
            return Outcome.ok(shots, resp.getPromptTokens(), resp.getCompletionTokens());
        } catch (BizException e) {
            log.warn("[shot-agent] LLM 业务异常 seriesId={} {} msg={}",
                    input.seriesId(), stage, e.getMessage());
            return Outcome.failed(false, DramaErrorText.modelCallFailed(e.getMessage()), 0L, 0L);
        } catch (RuntimeException e) {
            log.warn("[shot-agent] LLM 异常 seriesId={} {} class={} msg={}",
                    input.seriesId(), stage, e.getClass().getSimpleName(), e.getMessage());
            return Outcome.failed(false, DramaErrorText.modelCallFailed(e.getMessage()), 0L, 0L);
        }
    }

    /**
     * 按场次拆片后逐片出镜、合并成一份分镜草稿。
     *
     * <p>整集一次出不完时（场次多、或思考模型把 max_tokens 烧光），按场次分组分别出镜，
     * 每片只带自己的场次与相关资产，最后把片内场号映射回全集场号再合并。
     *
     * <p>各片之间互不依赖（每片自带剧本与资产），所以并发跑（并发度 {@link #MAX_SPLIT_CONCURRENCY}，
     * 与 v1 一致）：片数多时能省掉一半等待。
     */
    private Outcome draftSplit(AgentContext ctx, ShotDraftReq input, ScriptDoc script,
                               int maxTokens, int depth) {
        List<List<ScriptDoc.Scene>> packs = DramaTextChunker.packScenes(script.getScenes());
        if (packs.size() <= 1) {
            return Outcome.failed(false, "剧本无法再切分", 0L, 0L);
        }
        int workers = Math.min(MAX_SPLIT_CONCURRENCY, packs.size());
        log.warn("[shot-agent] 整本输出不成形，按场次拆片重跑 episodeId={} packs={} depth={} concurrency={}",
                ctx.episodeId(), packs.size(), depth, workers);

        // 每片写各自的槽位：并发下完成顺序不定，合并必须按片序（= 场次顺序），不能按完成顺序。
        PackResult[] parts = new PackResult[packs.size()];
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<?>> futures = new ArrayList<>(packs.size());
            for (int i = 0; i < packs.size(); i++) {
                final int index = i;
                final List<ScriptDoc.Scene> pack = packs.get(i);
                futures.add(pool.submit(() -> {
                    try {
                        parts[index] = draftPack(ctx, input, pack, maxTokens, depth,
                                "pack=" + (index + 1) + "/" + packs.size());
                    } catch (RuntimeException e) {
                        log.warn("[shot-agent] 分片异常已跳过 episodeId={} pack={}/{} msg={}",
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
                    log.warn("[shot-agent] 分镜并发被中断 episodeId={}", ctx.episodeId());
                    break;
                } catch (ExecutionException e) {
                    log.warn("[shot-agent] 分镜并发任务异常 episodeId={} msg={}", ctx.episodeId(),
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

        // 按片序合并：镜头要按场次先后落库。
        List<ShotDraftList.ShotDraft> shots = new ArrayList<>();
        Map<Integer, ShotDraftList.ChapterDraft> chapters = new LinkedHashMap<>();
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
            for (ShotDraftList.ChapterDraft chapter : part.chapters()) {
                if (chapter == null) continue;
                Integer key = chapter.getIndex() == null ? chapter.getSceneIndex() : chapter.getIndex();
                if (key != null) {
                    chapters.putIfAbsent(key, chapter);
                }
            }
            shots.addAll(part.shots());
        }
        if (shots.isEmpty()) {
            return Outcome.failed(false, "拆片后每片都没出分镜", promptTokens, completionTokens);
        }
        if (failedPacks > 0) {
            log.warn("[shot-agent] 部分分片失败 episodeId={} failed={} keptShots={}",
                    ctx.episodeId(), failedPacks, shots.size());
        }

        ShotDraftList merged = new ShotDraftList();
        merged.setChapters(new ArrayList<>(chapters.values()));
        merged.setShots(shots);
        return Outcome.ok(merged, promptTokens, completionTokens);
    }

    /** 单片出镜：还是不成形就把这片再对半分（深度上限 {@link #MAX_SPLIT_DEPTH}）。 */
    private PackResult draftPack(AgentContext ctx, ShotDraftReq input, List<ScriptDoc.Scene> pack,
                                 int maxTokens, int depth, String stage) {
        Outcome outcome = requestOnce(ctx, chunkRequest(input, pack), maxTokens, stage);
        if (outcome.shots() != null && outcome.shots().getShots() != null) {
            // 片内场号是 1..N 的局部编号，合并前要映射回全集场号。
            remapChunk(outcome.shots(), pack);
            return new PackResult(outcome.shots().getShots(), safe(outcome.shots().getChapters()),
                    outcome.promptTokens(), outcome.completionTokens(), 0);
        }
        if (depth < MAX_SPLIT_DEPTH && pack.size() > 1) {
            int mid = Math.max(1, pack.size() / 2);
            log.warn("[shot-agent] 分片仍不成形，对半再拆 episodeId={} {} scenes={} -> {}+{}",
                    ctx.episodeId(), stage, pack.size(), mid, pack.size() - mid);
            PackResult left = draftPack(ctx, input, pack.subList(0, mid), maxTokens, depth + 1, stage + ".a");
            PackResult right = draftPack(ctx, input, pack.subList(mid, pack.size()),
                    maxTokens, depth + 1, stage + ".b");
            return PackResult.merge(left, right);
        }
        return new PackResult(List.of(), List.of(), outcome.promptTokens(), outcome.completionTokens(), 1);
    }

    /** 一个分片对应的请求：只带这几场的剧本与相关资产，场号在片内从 1 重新编号。 */
    private ShotDraftReq chunkRequest(ShotDraftReq input, List<ScriptDoc.Scene> pack) {
        ScriptDoc chunk = scriptChunk(input.script(), pack);
        return new ShotDraftReq(input.seriesId(), input.episodeId(), chunk,
                restrictAssets(input.assets(), pack), input.memoryBlock(), input.feedback());
    }

    /**
     * 分片剧本：只保留这几场，场号在片内从 1 重新编号。
     *
     * <p>场号必须重编号：模型照着提示里的场号填 chapter_index，片内统一从 1 开始才不会
     * 撞车；出片后再映射回全集场号。原剧本不能被改 —— 章节按原场次号建。
     */
    private static ScriptDoc scriptChunk(ScriptDoc script, List<ScriptDoc.Scene> pack) {
        ScriptDoc chunk = new ScriptDoc();
        if (script != null) {
            chunk.setTitle(script.getTitle());
            chunk.setLogline(script.getLogline());
            chunk.setStylePrefix(script.getStylePrefix());
        }
        List<ScriptDoc.Scene> local = new ArrayList<>(pack.size());
        for (int i = 0; i < pack.size(); i++) {
            ScriptDoc.Scene copy = copyScene(pack.get(i));
            copy.setIndex(i + 1);
            local.add(copy);
        }
        chunk.setScenes(local);
        return chunk;
    }

    private static ScriptDoc.Scene copyScene(ScriptDoc.Scene source) {
        ScriptDoc.Scene copy = new ScriptDoc.Scene();
        if (source == null) {
            return copy;
        }
        copy.setIndex(source.getIndex());
        copy.setSlug(source.getSlug());
        copy.setHeading(source.getHeading());
        copy.setCharacters(source.getCharacters() == null ? new ArrayList<>() : new ArrayList<>(source.getCharacters()));
        copy.setProps(source.getProps() == null ? new ArrayList<>() : new ArrayList<>(source.getProps()));
        copy.setLocation(source.getLocation());
        copy.setTime(source.getTime());
        copy.setInterior(source.getInterior());
        copy.setAction(source.getAction());
        copy.setSound(source.getSound());
        if (source.getDialogue() == null) {
            copy.setDialogue(new ArrayList<>());
        } else {
            List<ScriptDoc.Dialogue> lines = new ArrayList<>(source.getDialogue().size());
            for (ScriptDoc.Dialogue line : source.getDialogue()) {
                if (line == null) {
                    continue;
                }
                ScriptDoc.Dialogue bit = new ScriptDoc.Dialogue();
                bit.setSpeaker(line.getSpeaker());
                bit.setLine(line.getLine());
                bit.setEmotion(line.getEmotion());
                lines.add(bit);
            }
            copy.setDialogue(lines);
        }
        return copy;
    }

    /**
     * 把分片里的局部场号映射回全集场号。
     *
     * <p>章节是按场次号建的（chapter_index = scene_index），映射错了分镜就落到别的场次上。
     */
    private static void remapChunk(ShotDraftList part, List<ScriptDoc.Scene> pack) {
        if (part == null) {
            return;
        }
        Map<Integer, Integer> localToAbs = new LinkedHashMap<>();
        for (int i = 0; i < pack.size(); i++) {
            ScriptDoc.Scene scene = pack.get(i);
            int absolute = scene.getIndex() == null || scene.getIndex() < 1 ? i + 1 : scene.getIndex();
            localToAbs.put(i + 1, absolute);
        }
        for (ShotDraftList.ChapterDraft chapter : safe(part.getChapters())) {
            if (chapter == null) {
                continue;
            }
            Integer mapped = localToAbs.get(chapter.getIndex() == null ? 1 : chapter.getIndex());
            if (mapped != null) {
                chapter.setIndex(mapped);
                chapter.setSceneIndex(mapped);
            }
        }
        for (ShotDraftList.ShotDraft shot : safe(part.getShots())) {
            if (shot == null) {
                continue;
            }
            Integer mapped = localToAbs.get(shot.getChapterIndex() == null ? 1 : shot.getChapterIndex());
            if (mapped != null) {
                shot.setChapterIndex(mapped);
            }
        }
    }

    /**
     * 分片只带本片用得到的资产名，避免把全集 20+ 资产塞进单片请求诱发超长空 shots（与 v1 同款）。
     */
    private static AssetDraftList restrictAssets(AssetDraftList assets, List<ScriptDoc.Scene> pack) {
        if (assets == null || assets.getAssets() == null || assets.getAssets().isEmpty()) {
            return assets;
        }
        Set<String> used = assetNamesForScenes(pack);
        if (used.isEmpty()) {
            return assets;
        }
        List<AssetDraftList.AssetDraft> rows = new ArrayList<>();
        for (AssetDraftList.AssetDraft draft : assets.getAssets()) {
            if (draft == null || draft.getName() == null) {
                continue;
            }
            if (used.contains(draft.getName().strip())) {
                rows.add(draft);
            }
        }
        if (rows.isEmpty()) {
            return assets;
        }
        AssetDraftList kept = new AssetDraftList();
        kept.setAssets(rows);
        return kept;
    }

    private static Set<String> assetNamesForScenes(List<ScriptDoc.Scene> pack) {
        Set<String> picked = new LinkedHashSet<>();
        for (ScriptDoc.Scene scene : pack) {
            if (scene == null) {
                continue;
            }
            addNames(picked, scene.getCharacters());
            addNames(picked, scene.getProps());
            for (String location : DramaAssetDrafts.sceneAssetLocations(scene.getLocation())) {
                if (location != null && !location.isBlank()) {
                    picked.add(location.strip());
                }
            }
        }
        return picked;
    }

    private static void addNames(Set<String> target, List<String> names) {
        if (names == null) {
            return;
        }
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                target.add(name.strip());
            }
        }
    }

    // ─────────────────────────────────────
    // 提示词
    // ─────────────────────────────────────

    private String buildSystem(String memoryBlock) {
        StringBuilder sb = new StringBuilder();
        sb.append(promptResolver.resolve("common")).append("\n\n");
        sb.append(promptResolver.resolve("schema_shots"));
        if (memoryBlock != null && !memoryBlock.isBlank()) {
            sb.append("\n\n").append(memoryBlock);
        }
        return sb.toString();
    }

    private String buildUser(ShotDraftReq input) {
        StringBuilder sb = new StringBuilder();
        sb.append("请把以下 ScriptDoc 切分为分镜草稿。\n\n");

        sb.append("【ScriptDoc 分场明细】\n");
        sb.append(renderSceneDigest(input.script()));

        if (input.assets() != null && input.assets().getAssets() != null
                && !input.assets().getAssets().isEmpty()) {
            sb.append("\n\n【资产名清单（仅引用，不可改名）】\n");
            String names = input.assets().getAssets().stream()
                    .map(a -> a.getName() + "(" + a.getType() + ")")
                    .collect(Collectors.joining("、"));
            sb.append(names);
        }
        if (input.feedback() != null && !input.feedback().isBlank()) {
            sb.append(input.feedback());
        }
        return sb.toString();
    }

    /**
     * 把 ScriptDoc 渲染为分场明细。
     *
     * <p>只给对白是不够的：模型看不到场的动作、地点、时间、内外景与道具，就写不出
     * 「40～120 字能拍出来的动作链」，也写不出有主色和光质的场景行 —— 出来的分镜
     * 只有一句对白，没有站位、没有镜头。这里把每场的可拍信息全给出去。
     */
    public static String renderSceneDigest(ScriptDoc script) {
        if (script == null || script.getScenes() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ScriptDoc.Scene scene : script.getScenes()) {
            if (scene == null) continue;
            sb.append("\n[场 ").append(scene.getIndex()).append("] ")
              .append(orEmpty(scene.getHeading())).append('\n');
            sb.append("  地点：").append(orEmpty(scene.getLocation()))
              .append(" ｜ 时间：").append(orEmpty(scene.getTime()))
              .append(" ｜ ").append(Boolean.TRUE.equals(scene.getInterior()) ? "内景" : "外景")
              .append('\n');
            appendLine(sb, "出场", scene.getCharacters());
            appendLine(sb, "道具", scene.getProps());
            if (hasText(scene.getAction())) {
                sb.append("  动作：").append(scene.getAction().strip()).append('\n');
            }
            if (hasText(scene.getSound())) {
                sb.append("  环境声：").append(scene.getSound().strip()).append('\n');
            }
            if (scene.getDialogue() == null) continue;
            for (ScriptDoc.Dialogue d : scene.getDialogue()) {
                if (d == null) continue;
                sb.append("  ").append(orEmpty(d.getSpeaker())).append('：')
                  .append(orEmpty(d.getLine())).append('\n');
            }
        }
        return sb.toString();
    }

    private static void appendLine(StringBuilder sb, String label, List<String> names) {
        if (names == null || names.isEmpty()) return;
        List<String> kept = new ArrayList<>();
        for (String name : names) {
            if (hasText(name)) kept.add(name.trim());
        }
        if (kept.isEmpty()) return;
        sb.append("  ").append(label).append('：').append(String.join("、", kept)).append('\n');
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private ShotDraftList parse(String content) {
        ShotDraftList list = AgentTruncationSupport.parseJson(objectMapper, content, ShotDraftList.class);
        if (list != null && list.getShots() != null && !list.getShots().isEmpty()) {
            return list;
        }
        // 整份 JSON 解析不出来时，shots 数组本身可能还是好的（章节那部分写坏了），
        // 单独把数组捞出来重拼一次（v1 老链路的同一招）。
        String array = AgentTruncationSupport.extractJsonArray(content, "shots");
        if (array == null) {
            return list;
        }
        ShotDraftList salvaged = AgentTruncationSupport.parseJson(objectMapper,
                "{\"shots\":" + array + "}", ShotDraftList.class);
        if (salvaged != null && salvaged.getShots() != null && !salvaged.getShots().isEmpty()) {
            log.info("[shot-agent] 分镜经 shots 数组抢救解析成功 count={}", salvaged.getShots().size());
            return salvaged;
        }
        return list;
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    /**
     * 一片分镜的结果。
     *
     * <p>每片自己一份，并发跑完后再按片序合并 —— 所以这里不需要共享计数器，
     * token 也不会因为并发而丢。
     */
    private record PackResult(List<ShotDraftList.ShotDraft> shots,
                              List<ShotDraftList.ChapterDraft> chapters,
                              long promptTokens, long completionTokens, int failedPacks) {
        static PackResult empty() {
            return new PackResult(List.of(), List.of(), 0L, 0L, 1);
        }

        /** 左右两半拼回一片：顺序必须保持左片在前（场次靠前的先）。 */
        static PackResult merge(PackResult left, PackResult right) {
            List<ShotDraftList.ShotDraft> shots = new ArrayList<>(left.shots());
            shots.addAll(right.shots());
            List<ShotDraftList.ChapterDraft> chapters = new ArrayList<>(left.chapters());
            chapters.addAll(right.chapters());
            return new PackResult(shots, chapters,
                    left.promptTokens() + right.promptTokens(),
                    left.completionTokens() + right.completionTokens(),
                    left.failedPacks() + right.failedPacks());
        }
    }

    /** 一次请求的结果。 */
    private record Outcome(ShotDraftList shots, boolean truncated, String reason,
                           long promptTokens, long completionTokens) {
        static Outcome ok(ShotDraftList shots, long promptTokens, long completionTokens) {
            return new Outcome(shots, false, null, promptTokens, completionTokens);
        }

        static Outcome failed(boolean truncated, String reason, long promptTokens, long completionTokens) {
            return new Outcome(null, truncated, reason, promptTokens, completionTokens);
        }
    }
}
