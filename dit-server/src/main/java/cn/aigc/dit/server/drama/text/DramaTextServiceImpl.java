package cn.aigc.dit.server.drama.text;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.server.drama.DramaAssetDrafts;
import cn.aigc.dit.server.drama.DramaContract;
import cn.aigc.dit.server.drama.DramaDurations;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 剧本理解层实现。分场、资产与分镜均使用系列绑定的文本模型；
 * 未绑定或输出合同连续失败时明确报错，不生成规则替代结果。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DramaTextServiceImpl implements DramaTextService {

    /** 模型偶尔会多写键名；合同外的键一律忽略而不是让整次解析失败。 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * 仅在严格解析失败后使用：容忍 MiniMax 等模型夹带的双斜线/块注释与尾逗号。
     * 合法 JSON 仍走 {@link #MAPPER}，成功路径行为不变。
     */
    private static final ObjectMapper LENIENT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(JsonParser.Feature.ALLOW_COMMENTS, true)
            .configure(JsonParser.Feature.ALLOW_TRAILING_COMMA, true)
            .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
            .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);

    private static final int LLM_JSON_RETRIES = 2;
    private static final int ASSET_ENRICHMENT_BATCH_SIZE = 4;
    /** 资产批次有限并发，墙钟近似降为 ceil(N/batch)/concurrency。 */
    private static final int ASSET_ENRICHMENT_CONCURRENCY = 2;
    /** 分镜分片有限并发，墙钟近似降为 ceil(packs)/concurrency。 */
    private static final int SHOT_DRAFT_CONCURRENCY = 2;
    static final int MAX_JSON_TOKENS = 32768;
    /** 单场只保留模型按重要性排序后的前三个核心道具，防止偶发越过提示词。 */
    private static final int MAX_MAIN_PROPS_PER_SCENE = 3;

    /**
     * 截断处理策略。分场/分镜禁止把残缺输出塞进 repair；资产仍可 bump+repair。
     */
    enum TruncationPolicy {
        /** 截断后加倍 max_tokens，并用 repairUser 附带上次残缺输出（资产路径）。 */
        BUMP_AND_REPAIR,
        /** 截断后只加倍 max_tokens、重放原任务；禁止 length-repair；仍截断则抛 {@link TruncatedOutputException}。 */
        BUMP_ONLY_NO_REPAIR
    }

    /** 输出被 max_tokens 截断且不宜再整包 repair 时抛出，供上层缩片重跑。 */
    static final class TruncatedOutputException extends RuntimeException {
        TruncatedOutputException(String reason) {
            super(reason);
        }
    }

    /**
     * 本线程最近一次 parseScript 的实际引擎，供 {@link #engine(long)} 与落库同源读取。
     * 调用方必须紧跟 parseScript 使用；按 seriesId 比对，避免读到上一条请求的残留。
     */
    private static final ThreadLocal<Long> PARSED_SERIES = new ThreadLocal<>();
    private static final ThreadLocal<String> PARSED_ENGINE = new ThreadLocal<>();

    private final TextChatGateway gateway;

    @Override
    public String engine(long seriesId) {
        if (Objects.equals(PARSED_SERIES.get(), seriesId) && StringUtils.hasText(PARSED_ENGINE.get())) {
            return PARSED_ENGINE.get();
        }
        return useLlm(seriesId) ? ENGINE_LLM : ENGINE_PENDING;
    }

    @Override
    public ScriptDoc parseScript(long seriesId, String raw) {
        if (!useLlm(seriesId)) {
            throw new BizException(cn.aigc.dit.common.exception.ErrorCode.BAD_REQUEST,
                    "项目未绑定文本模型，请先在基础信息中选择文本模型");
        }
        long started = System.nanoTime();
        log.info("[drama-text] 分场开始 seriesId={} rawLength={}", seriesId, raw == null ? 0 : raw.length());
        try {
            ScriptDoc result = record(seriesId, ENGINE_LLM, llmParse(seriesId, raw));
            log.info("[drama-text] 分场完成 seriesId={} scenes={} elapsedMs={}",
                    seriesId, result.getScenes() == null ? 0 : result.getScenes().size(), elapsedMs(started));
            return result;
        } catch (RuntimeException e) {
            log.warn("[drama-text] 分场失败 seriesId={} elapsedMs={} error={}",
                    seriesId, elapsedMs(started), e.getMessage());
            throw e;
        }
    }

    @Override
    public AssetDraftList extractAssets(long seriesId, ScriptDoc script) {
        if (script == null || script.getScenes().isEmpty()) {
            throw new BizException(cn.aigc.dit.common.exception.ErrorCode.BAD_REQUEST, "剧本场景为空，无法抽取资产");
        }
        return enrichAssets(seriesId, script, DramaAssetDrafts.fromScript(script));
    }

    @Override
    public AssetDraftList enrichAssets(long seriesId, ScriptDoc script, AssetDraftList skeleton) {
        if (skeleton == null || skeleton.getAssets() == null || skeleton.getAssets().isEmpty()) {
            return skeleton == null ? new AssetDraftList() : skeleton;
        }
        if (script == null || script.getScenes() == null || script.getScenes().isEmpty()) {
            return skeleton;
        }
        long started = System.nanoTime();
        log.info("[drama-text] 资产增强开始 seriesId={} assets={}", seriesId, skeleton.getAssets().size());
        try {
            if (!useLlm(seriesId)) {
                log.info("[drama-text] 未绑定文本模型，保留确定性资产骨架 seriesId={}", seriesId);
                return skeleton;
            }
        } catch (RuntimeException e) {
            log.warn("[drama-text] 文本模型不可用，保留确定性资产骨架 seriesId={} error={}", seriesId, e.getMessage());
            return skeleton;
        }
        List<AssetDraftList.AssetDraft> assets = skeleton.getAssets();
        List<int[]> ranges = new ArrayList<>();
        for (int from = 0; from < assets.size(); from += ASSET_ENRICHMENT_BATCH_SIZE) {
            ranges.add(new int[]{from, Math.min(from + ASSET_ENRICHMENT_BATCH_SIZE, assets.size())});
        }
        if (ranges.size() == 1) {
            enrichAssetBatch(seriesId, script, assets, ranges.get(0)[0], ranges.get(0)[1]);
        } else {
            int workers = Math.min(ASSET_ENRICHMENT_CONCURRENCY, ranges.size());
            ExecutorService pool = Executors.newFixedThreadPool(workers);
            try {
                List<Future<?>> futures = new ArrayList<>(ranges.size());
                for (int[] range : ranges) {
                    int from = range[0];
                    int to = range[1];
                    futures.add(pool.submit(() -> enrichAssetBatch(seriesId, script, assets, from, to)));
                }
                for (Future<?> future : futures) {
                    try {
                        future.get();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("[drama-text] 资产增强并发被中断 seriesId={}", seriesId);
                        break;
                    } catch (ExecutionException e) {
                        log.warn("[drama-text] 资产增强并发任务异常 seriesId={} error={}",
                                seriesId, e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
                    }
                }
            } finally {
                pool.shutdown();
                try {
                    if (!pool.awaitTermination(2, TimeUnit.MINUTES)) {
                        pool.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    pool.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }
        log.info("[drama-text] 资产增强完成 seriesId={} assets={} elapsedMs={}",
                seriesId, assets.size(), elapsedMs(started));
        return skeleton;
    }

    private void enrichAssetBatch(
            long seriesId, ScriptDoc script, List<AssetDraftList.AssetDraft> assets, int from, int to) {
        AssetDraftList batch = new AssetDraftList();
        batch.setAssets(new ArrayList<>(assets.subList(from, to)));
        try {
            AssetDraftList enriched = attemptJson(seriesId, DramaTextPrompts.assetSystem(),
                    DramaTextPrompts.assetEnrichmentUser(toJson(script), toJson(batch)),
                    content -> normalizeAssets(readList(content, AssetDraftList.class)), "AssetDraftList", 16384);
            mergeAssetEnrichment(batch, enriched);
        } catch (RuntimeException e) {
            log.warn("[drama-text] 资产批次增强失败 seriesId={} from={} to={} error={}",
                    seriesId, from, to, e.getMessage());
        }
    }

    static void mergeAssetEnrichment(AssetDraftList skeleton, AssetDraftList enriched) {
        if (skeleton == null || enriched == null || skeleton.getAssets() == null || enriched.getAssets() == null) {
            return;
        }
        Map<String, AssetDraftList.AssetDraft> updates = new HashMap<>();
        for (AssetDraftList.AssetDraft item : enriched.getAssets()) {
            if (item == null || !StringUtils.hasText(item.getName()) || !StringUtils.hasText(item.getType())) {
                continue;
            }
            updates.put(item.getType().strip() + "\0" + item.getName().strip(), item);
        }
        for (AssetDraftList.AssetDraft target : skeleton.getAssets()) {
            if (target == null || !StringUtils.hasText(target.getName()) || !StringUtils.hasText(target.getType())) {
                continue;
            }
            AssetDraftList.AssetDraft update = updates.get(target.getType().strip() + "\0" + target.getName().strip());
            if (update == null) {
                continue;
            }
            copyIfText(update.getDescription(), target::setDescription);
            copyIfText(update.getIdentityInvariants(), target::setIdentityInvariants);
            if (StringUtils.hasText(update.getImagePrompt())
                    && !DramaAssetDrafts.shouldReplaceImagePrompt(update.getImagePrompt())) {
                target.setImagePrompt(update.getImagePrompt().strip());
            }
            copyIfText(update.getVoiceLock(), target::setVoiceLock);
            target.setNeedsHuman(!(StringUtils.hasText(target.getDescription())
                    && StringUtils.hasText(target.getIdentityInvariants())
                    && StringUtils.hasText(target.getImagePrompt())));
        }
    }

    private static void copyIfText(String value, java.util.function.Consumer<String> setter) {
        if (StringUtils.hasText(value)) {
            setter.accept(value.strip());
        }
    }

    @Override
    public ShotDraftList draftShots(long seriesId, ScriptDoc script, List<String> assetNames) {
        return draftShots(seriesId, script, assetNames, DramaContract.DEFAULT_DURATION_SEC);
    }

    @Override
    public ShotDraftList draftShots(long seriesId, ScriptDoc script, List<String> assetNames, int durationSec) {
        return draftShots(seriesId, script, assetNames, durationSec, null);
    }

    @Override
    public ShotDraftList draftShots(
            long seriesId,
            ScriptDoc script,
            List<String> assetNames,
            int durationSec,
            Integer episodeBudgetSec) {
        int duration = DramaDurations.clamp(durationSec);
        if (script == null || script.getScenes().isEmpty()) {
            throw new BizException(cn.aigc.dit.common.exception.ErrorCode.BAD_REQUEST, "剧本场景为空，无法生成分镜");
        }
        if (!useLlm(seriesId)) {
            throw new BizException(cn.aigc.dit.common.exception.ErrorCode.BAD_REQUEST,
                    "项目未绑定文本模型，请先在基础信息中选择文本模型");
        }
        long started = System.nanoTime();
        log.info("[drama-text] 分镜草稿开始 seriesId={} scenes={} assets={} durationSec={}",
                seriesId, script.getScenes().size(), safe(assetNames).size(), duration);
        try {
            ShotDraftList drafts = llmDraftShots(seriesId, script, safe(assetNames), duration, episodeBudgetSec);
            int before = drafts.getShots() == null ? 0 : drafts.getShots().size();
            ShotDraftList result = ShotDraftMinimizer.minimize(drafts, DramaContract.MAX_DURATION_SEC);
            log.info("[drama-text] 分镜草稿完成 seriesId={} shotsBefore={} shotsAfter={} elapsedMs={}",
                    seriesId, before, result.getShots() == null ? 0 : result.getShots().size(), elapsedMs(started));
            return result;
        } catch (RuntimeException e) {
            log.warn("[drama-text] 分镜草稿失败 seriesId={} elapsedMs={} error={}",
                    seriesId, elapsedMs(started), e.getMessage());
            throw e;
        }
    }

    /**
     * 只认系列绑定，不认环境变量：{@code drama_series.text_model_id → ai_model}。
     * 用 {@code resolve} 而非 {@code llmAvailable}——后者把「绑了但已停用」压成 false，
     * 否则会把「已绑定但已停用」错误当成未绑定，丢失 {@link TextChatGateway#BINDING_UNUSABLE} 的换绑提示。
     */
    private boolean useLlm(long seriesId) {
        return gateway.resolve(seriesId).isPresent();
    }

    private ScriptDoc llmParse(long seriesId, String raw) {
        List<String> chunks = DramaTextChunker.packRawChunks(raw);
        if (chunks.size() <= 1) {
            return parseRawChunk(seriesId, raw, 1, 1);
        }
        log.info("[drama-text] 分场分片 seriesId={} chunks={} rawLength={}",
                seriesId, chunks.size(), raw == null ? 0 : raw.length());
        return mergeParsedChunks(seriesId, chunks);
    }

    private ScriptDoc mergeParsedChunks(long seriesId, List<String> chunks) {
        ScriptDoc merged = new ScriptDoc();
        List<ScriptDoc.Scene> scenes = new ArrayList<>();
        int total = Math.max(1, chunks.size());
        int failed = 0;
        String lastError = null;
        for (int i = 0; i < chunks.size(); i++) {
            int chunkIndex = i + 1;
            try {
                ScriptDoc part = parseRawChunk(seriesId, chunks.get(i), chunkIndex, total);
                if (scenes.isEmpty()) {
                    merged.setTitle(part.getTitle());
                    merged.setLogline(part.getLogline());
                    merged.setStylePrefix(part.getStylePrefix());
                }
                scenes.addAll(safe(part.getScenes()));
            } catch (RuntimeException e) {
                failed++;
                lastError = e.getMessage();
                log.warn("[drama-text] 分场分片失败已跳过 seriesId={} chunk={}/{} error={}",
                        seriesId, chunkIndex, total, e.getMessage());
            }
        }
        merged.setScenes(scenes);
        ScriptDoc normalized = normalize(merged);
        if (normalized == null) {
            throw new BizException(cn.aigc.dit.common.exception.ErrorCode.BAD_REQUEST,
                    "文本模型输出未通过 ScriptDoc 合同："
                            + (lastError != null ? lastError : "分片合并后 scenes 为空"));
        }
        if (failed > 0) {
            log.warn("[drama-text] 分场部分分片失败 seriesId={} failedChunks={} keptScenes={}",
                    seriesId, failed, normalized.getScenes().size());
        }
        return normalized;
    }

    /**
     * 解析一片原文。截断或合同失败且仍可细拆时再切；禁止把残缺大输出塞进 length-repair。
     * 仅影响分场；拆镜路径不经过此处。
     */
    private ScriptDoc parseRawChunk(long seriesId, String raw, int chunkIndex, int chunkTotal) {
        String user = chunkTotal <= 1
                ? DramaTextPrompts.scriptUser(raw)
                : DramaTextPrompts.scriptUserChunk(raw, chunkIndex, chunkTotal);
        try {
            log.info("[drama-text] 分场分片请求 seriesId={} chunk={}/{} length={}",
                    seriesId, chunkIndex, chunkTotal, raw == null ? 0 : raw.length());
            return attemptJson(seriesId, DramaTextPrompts.scriptSystem(), user,
                    DramaTextServiceImpl::readScript, "ScriptDoc", 16384,
                    TruncationPolicy.BUMP_ONLY_NO_REPAIR);
        } catch (TruncatedOutputException truncated) {
            return resplitParseChunk(seriesId, raw, chunkIndex, chunkTotal, truncated.getMessage());
        } catch (BizException contractFailed) {
            // MiniMax 常见：finish_reason=stop 但 JSON 非法/空 scenes，同样尝试细拆。
            return resplitParseChunk(seriesId, raw, chunkIndex, chunkTotal, contractFailed.getMessage());
        }
    }

    private ScriptDoc resplitParseChunk(
            long seriesId, String raw, int chunkIndex, int chunkTotal, String reason) {
        List<String> finer = DramaTextChunker.packRawChunksAfterTruncation(raw);
        if (finer.size() <= 1) {
            throw new BizException(cn.aigc.dit.common.exception.ErrorCode.BAD_REQUEST,
                    reason == null ? "分场输出未通过合同" : reason);
        }
        log.warn("[drama-text] 分场失败后细拆重跑 seriesId={} chunk={}/{} length={} -> subChunks={} reason={}",
                seriesId, chunkIndex, chunkTotal, raw == null ? 0 : raw.length(), finer.size(), reason);
        return mergeParsedChunks(seriesId, finer);
    }

    private ShotDraftList llmDraftShots(
            long seriesId,
            ScriptDoc script,
            List<String> assetNames,
            int duration,
            Integer episodeBudgetSec) {
        List<List<ScriptDoc.Scene>> packs = DramaTextChunker.packScenes(script.getScenes());
        log.info("[drama-text] 分镜分片 seriesId={} chunks={} scenes={} concurrency={}",
                seriesId, packs.size(), script.getScenes().size(),
                Math.min(SHOT_DRAFT_CONCURRENCY, Math.max(1, packs.size())));
        int chunkTotal = Math.max(1, packs.size());
        @SuppressWarnings("unchecked")
        List<ShotDraftList.ShotDraft>[] parts = new List[packs.size()];
        RuntimeException[] errors = new RuntimeException[packs.size()];
        if (packs.size() <= 1) {
            try {
                parts[0] = draftShotPack(
                        seriesId, script, assetNames, duration, episodeBudgetSec,
                        packs.get(0), 1, chunkTotal);
            } catch (RuntimeException e) {
                errors[0] = e;
                log.warn("[drama-text] 分镜分片失败已跳过 seriesId={} chunk={}/{} error={}",
                        seriesId, 1, chunkTotal, e.getMessage());
            }
        } else {
            int workers = Math.min(SHOT_DRAFT_CONCURRENCY, packs.size());
            ExecutorService pool = Executors.newFixedThreadPool(workers);
            try {
                List<Future<?>> futures = new ArrayList<>(packs.size());
                for (int i = 0; i < packs.size(); i++) {
                    final int idx = i;
                    futures.add(pool.submit(() -> {
                        try {
                            parts[idx] = draftShotPack(
                                    seriesId, script, assetNames, duration, episodeBudgetSec,
                                    packs.get(idx), idx + 1, chunkTotal);
                        } catch (RuntimeException e) {
                            errors[idx] = e;
                            log.warn("[drama-text] 分镜分片失败已跳过 seriesId={} chunk={}/{} error={}",
                                    seriesId, idx + 1, chunkTotal, e.getMessage());
                        }
                    }));
                }
                for (Future<?> future : futures) {
                    try {
                        future.get();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("[drama-text] 分镜并发被中断 seriesId={}", seriesId);
                        break;
                    } catch (ExecutionException e) {
                        log.warn("[drama-text] 分镜并发任务异常 seriesId={} error={}",
                                seriesId, e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
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
        }
        ShotDraftList merged = new ShotDraftList();
        List<ShotDraftList.ShotDraft> shots = new ArrayList<>();
        int failedChunks = 0;
        String lastChunkError = null;
        for (int i = 0; i < packs.size(); i++) {
            if (errors[i] != null) {
                failedChunks++;
                lastChunkError = errors[i].getMessage();
                continue;
            }
            if (parts[i] != null) {
                shots.addAll(parts[i]);
            }
        }
        merged.setShots(shots);
        ShotDraftList normalized = clampDurations(normalizeShots(merged, script, assetNames), duration);
        if (normalized == null || normalized.getShots() == null || normalized.getShots().isEmpty()) {
            throw new BizException(cn.aigc.dit.common.exception.ErrorCode.BAD_REQUEST,
                    "文本模型输出未通过 ShotDraftList 合同："
                            + (lastChunkError != null ? lastChunkError : "分片合并后 shots 为空"));
        }
        if (failedChunks > 0) {
            log.warn("[drama-text] 分镜部分分片失败 seriesId={} failedChunks={} keptShots={}",
                    seriesId, failedChunks, normalized.getShots().size());
        }
        return normalized;
    }

    /**
     * 拆一片场次；截断时若片内仍有多场则对半分再跑，禁止 length-repair 附带残缺输出。
     * 单场合同失败向上抛，由 {@link #llmDraftShots} 决定跳过或整集失败。
     */
    private List<ShotDraftList.ShotDraft> draftShotPack(
            long seriesId,
            ScriptDoc script,
            List<String> assetNames,
            int duration,
            Integer episodeBudgetSec,
            List<ScriptDoc.Scene> pack,
            int chunkIndex,
            int chunkTotal) {
        ScriptDoc chunkDoc = scriptChunk(script, pack);
        String assets = String.join("、", assetsForScenes(pack, assetNames));
        String user = chunkTotal <= 1 && pack.size() == script.getScenes().size()
                ? DramaTextPrompts.shotUser(toJson(chunkDoc), assets, duration, episodeBudgetSec)
                : DramaTextPrompts.shotUserChunk(
                        toJson(chunkDoc), assets, duration, episodeBudgetSec, chunkIndex, chunkTotal);
        try {
            log.info("[drama-text] 分镜分片请求 seriesId={} chunk={}/{} scenes={} assetNames={}",
                    seriesId, chunkIndex, chunkTotal, pack.size(), assets);
            ShotDraftList part = attemptJson(seriesId, DramaTextPrompts.shotSystem(), user,
                    content -> clampDurations(
                            normalizeShots(readShots(content), chunkDoc, assetNames),
                            duration),
                    "ShotDraftList", 16384, TruncationPolicy.BUMP_ONLY_NO_REPAIR);
            remapShotChunk(part, pack);
            return part.getShots() == null ? List.of() : part.getShots();
        } catch (TruncatedOutputException truncated) {
            if (pack.size() <= 1) {
                throw new BizException(cn.aigc.dit.common.exception.ErrorCode.BAD_REQUEST,
                        "文本模型输出未通过 ShotDraftList 合同：" + truncated.getMessage());
            }
            int mid = Math.max(1, pack.size() / 2);
            log.warn("[drama-text] 分镜截断后缩片重跑 seriesId={} chunk={}/{} scenes={} -> {}+{}",
                    seriesId, chunkIndex, chunkTotal, pack.size(), mid, pack.size() - mid);
            List<ShotDraftList.ShotDraft> shots = new ArrayList<>();
            shots.addAll(draftShotPack(seriesId, script, assetNames, duration, episodeBudgetSec,
                    pack.subList(0, mid), chunkIndex, chunkTotal));
            shots.addAll(draftShotPack(seriesId, script, assetNames, duration, episodeBudgetSec,
                    pack.subList(mid, pack.size()), chunkIndex, chunkTotal));
            return shots;
        }
    }

    /**
     * 分镜提示只带本场相关资产名，避免把全集 20+ 资产塞进单场请求诱发超长空 shots。
     */
    static List<String> assetsForScenes(List<ScriptDoc.Scene> scenes, List<String> allowed) {
        Set<String> allow = new LinkedHashSet<>(safe(allowed));
        if (allow.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> picked = new LinkedHashSet<>();
        for (ScriptDoc.Scene scene : safe(scenes)) {
            if (scene == null) {
                continue;
            }
            for (String name : safe(scene.getCharacters())) {
                if (StringUtils.hasText(name) && allow.contains(name.strip())) {
                    picked.add(name.strip());
                }
            }
            for (String name : safe(scene.getProps())) {
                if (StringUtils.hasText(name) && allow.contains(name.strip())) {
                    picked.add(name.strip());
                }
            }
            if (StringUtils.hasText(scene.getLocation()) && allow.contains(scene.getLocation().strip())) {
                picked.add(scene.getLocation().strip());
            }
        }
        if (picked.isEmpty()) {
            return new ArrayList<>(allow);
        }
        return new ArrayList<>(picked);
    }

    private <T> T attemptJson(long seriesId, String system, String user,
                              java.util.function.Function<String, T> reader, String what, int maxTokens) {
        return attemptJson(seriesId, system, user, reader, what, maxTokens, TruncationPolicy.BUMP_AND_REPAIR);
    }

    private <T> T attemptJson(long seriesId, String system, String user,
                              java.util.function.Function<String, T> reader, String what, int maxTokens,
                              TruncationPolicy truncationPolicy) {
        String requestUser = user;
        String lastReason = "输出未通过合同校验";
        int tokens = maxTokens;
        TruncationPolicy policy = truncationPolicy == null
                ? TruncationPolicy.BUMP_AND_REPAIR
                : truncationPolicy;
        for (int attempt = 1; attempt <= LLM_JSON_RETRIES; attempt++) {
            TextChatRequest req = new TextChatRequest(system, requestUser, true, tokens);
            log.info("[drama-text] 模型请求开始 contract={} seriesId={} attempt={} maxTokens={} userLength={}",
                    what, seriesId, attempt, tokens, requestUser == null ? 0 : requestUser.length());
            TextChatResult response = gateway.chat(seriesId, req);
            String content = response.getContent();
            T value = reader.apply(content);
            if (value != null) {
                if (attempt > 1) {
                    log.info("[drama-text] {} 第 {} 次输出符合合同 seriesId={}", what, attempt, seriesId);
                }
                return value;
            }
            String reason = invalidOutputReason(content, what);
            boolean truncated = truncatedByLength(response.getFinishReason());
            if (truncated) {
                reason = "输出被 max_tokens 截断（finish_reason=" + response.getFinishReason() + "）。" + reason;
            }
            lastReason = reason;
            log.warn("[drama-text] {} 输出不符合合同 seriesId={} attempt={} reason={} model={} finishReason={} contentLength={} nextMaxTokens={} truncationPolicy={}",
                    what, seriesId, attempt, reason, response.getModel(),
                    response.getFinishReason(), content == null ? 0 : content.length(),
                    truncated ? nextMaxTokens(tokens) : tokens, policy);
            if (truncated && policy == TruncationPolicy.BUMP_ONLY_NO_REPAIR) {
                int bumped = nextMaxTokens(tokens);
                if (bumped > tokens) {
                    tokens = bumped;
                    requestUser = user;
                    continue;
                }
                throw new TruncatedOutputException(reason);
            }
            if (policy == TruncationPolicy.BUMP_ONLY_NO_REPAIR) {
                // 禁止把 MiniMax 大段非法 JSON 塞进 repair（会二次撑爆/截断）；原任务再试一次。
                requestUser = user;
                continue;
            }
            if (truncated) {
                tokens = nextMaxTokens(tokens);
            }
            String previous = content;
            if ("ShotDraftList".equals(what) && content != null && content.length() > 4000) {
                previous = content.substring(0, 500) + "\n…(已截断过长无效输出，请只返回非空 shots 数组)…";
            }
            requestUser = DramaTextPrompts.repairUser(user, what, reason, previous);
        }
        if (policy == TruncationPolicy.BUMP_ONLY_NO_REPAIR
                && lastReason != null
                && lastReason.contains("max_tokens 截断")) {
            throw new TruncatedOutputException(lastReason);
        }
        throw new BizException(cn.aigc.dit.common.exception.ErrorCode.BAD_REQUEST,
                "文本模型输出未通过 " + what + " 合同：" + lastReason);
    }

    private static ScriptDoc scriptChunk(ScriptDoc full, List<ScriptDoc.Scene> pack) {
        ScriptDoc chunk = new ScriptDoc();
        if (full != null) {
            chunk.setTitle(full.getTitle());
            chunk.setLogline(full.getLogline());
            chunk.setStylePrefix(full.getStylePrefix());
        }
        List<ScriptDoc.Scene> local = new ArrayList<>(pack.size());
        for (int i = 0; i < pack.size(); i++) {
            ScriptDoc.Scene source = pack.get(i);
            ScriptDoc.Scene copy = copyScene(source);
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
                lines.add(bit);
            }
            copy.setDialogue(lines);
        }
        return copy;
    }

    /**
     * 把分片内 1..k 的 chapter_index 映射回全集场次号。
     */
    static void remapShotChunk(ShotDraftList part, List<ScriptDoc.Scene> absoluteScenes) {
        if (part == null || absoluteScenes == null || absoluteScenes.isEmpty()) {
            return;
        }
        Map<Integer, Integer> localToAbs = new HashMap<>();
        for (int i = 0; i < absoluteScenes.size(); i++) {
            ScriptDoc.Scene scene = absoluteScenes.get(i);
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

    static boolean truncatedByLength(String finishReason) {
        if (!StringUtils.hasText(finishReason)) {
            return false;
        }
        String value = finishReason.trim().toLowerCase();
        return "length".equals(value) || "max_tokens".equals(value);
    }

    static int nextMaxTokens(int current) {
        int safe = Math.max(1, current);
        return (int) Math.min(MAX_JSON_TOKENS, (long) safe * 2);
    }

    /**
     * 失败时重新做一次只读诊断。只记录结构原因和长度，不把用户剧本或模型原文写进日志。
     */
    static String invalidOutputReason(String content, String what) {
        if (!StringUtils.hasText(content)) {
            return "模型返回 content 为空";
        }
        String json = extractJsonObject(content);
        if (json == null) {
            return "返回内容中没有完整 JSON 对象";
        }
        Class<?> type = switch (what) {
            case "ScriptDoc" -> ScriptDoc.class;
            case "AssetDraftList" -> AssetDraftList.class;
            case "ShotDraftList" -> ShotDraftList.class;
            default -> null;
        };
        if (type == null) {
            return "未知输出合同 " + what;
        }
        try {
            Object parsed;
            try {
                parsed = MAPPER.readValue(json, type);
            } catch (JsonProcessingException strict) {
                parsed = LENIENT_MAPPER.readValue(json, type);
            }
            if (parsed instanceof ScriptDoc doc) {
                if (doc.getScenes() == null || doc.getScenes().isEmpty()) {
                    return "scenes 缺失或为空";
                }
                return "场景经合同清洗后为空（场景对象为空或缺少有效内容）";
            }
            if (parsed instanceof AssetDraftList list) {
                if (list.getAssets() == null || list.getAssets().isEmpty()) {
                    return "assets 缺失或为空";
                }
                return "资产经合同清洗后为空（name/type 缺失或类型无效）";
            }
            if (parsed instanceof ShotDraftList list) {
                if (list.getShots() == null || list.getShots().isEmpty()) {
                    if (content.length() > 4000) {
                        return "shots 缺失或为空（输出过长 contentLength=" + content.length()
                                + "，请只返回含非空 shots 数组的最小 JSON，禁止长篇解释）";
                    }
                    return "shots 缺失或为空";
                }
                return "分镜经合同清洗后为空（章节引用、名称或字段无效）";
            }
            return "输出未通过合同校验";
        } catch (JsonProcessingException e) {
            String message = e.getOriginalMessage();
            if (!StringUtils.hasText(message)) {
                message = e.getClass().getSimpleName();
            }
            String location = e.getLocation() == null ? "" : " line=" + e.getLocation().getLineNr()
                    + " column=" + e.getLocation().getColumnNr();
            return "JSON 解析失败" + location + ": " + clipDiagnostic(message);
        }
    }

    private static String clipDiagnostic(String value) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return text.length() <= 240 ? text : text.substring(0, 240);
    }

    private String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    static ScriptDoc readScript(String content) {
        ScriptDoc doc = parseJson(content, ScriptDoc.class);
        return doc == null ? null : normalize(doc);
    }

    /**
     * 读取分镜 JSON；若外层对象 shots 为空但正文里另有 shots 数组（MiniMax 常见），尝试抢救。
     */
    static ShotDraftList readShots(String content) {
        ShotDraftList list = parseJson(content, ShotDraftList.class);
        if (list != null && list.getShots() != null && !list.getShots().isEmpty()) {
            return list;
        }
        String array = extractJsonArrayAfterKey(content, "shots");
        if (array == null) {
            return list;
        }
        ShotDraftList salvaged = parseJson("{\"shots\":" + array + "}", ShotDraftList.class);
        if (salvaged != null && salvaged.getShots() != null && !salvaged.getShots().isEmpty()) {
            log.info("[drama-text] ShotDraftList 经 shots 数组抢救解析成功 count={}", salvaged.getShots().size());
            return salvaged;
        }
        return list;
    }

    /** 模型可能包代码围栏或前后带解释文字；取最外层花括号。 */
    private static String extractJsonObject(String content) {
        if (!StringUtils.hasText(content)) {
            return null;
        }
        String text = stripFence(content);
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : null;
    }

    /**
     * 在正文中定位 {@code "key": [ ... ]} 的数组（括号配对），优先返回非空数组。
     */
    static String extractJsonArrayAfterKey(String content, String key) {
        if (!StringUtils.hasText(content) || !StringUtils.hasText(key)) {
            return null;
        }
        String text = stripFence(content);
        String quoted = "\"" + key + "\"";
        String empty = null;
        int from = 0;
        while (from < text.length()) {
            int keyAt = text.indexOf(quoted, from);
            if (keyAt < 0) {
                break;
            }
            String array = extractArrayAfter(text, keyAt + quoted.length());
            from = keyAt + quoted.length();
            if (array == null) {
                continue;
            }
            String compact = array.replaceAll("\\s+", "");
            if ("[]".equals(compact)) {
                if (empty == null) {
                    empty = array;
                }
                continue;
            }
            return array;
        }
        return empty;
    }

    private static String extractArrayAfter(String text, int afterKey) {
        int colon = text.indexOf(':', afterKey);
        if (colon < 0) {
            return null;
        }
        int start = colon + 1;
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        if (start >= text.length() || text.charAt(start) != '[') {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static String stripFence(String content) {
        return content.strip().replaceAll("^```[a-zA-Z]*", "").replaceAll("```$", "").strip();
    }

    /**
     * 先严格解析；失败再容忍注释、尾逗号、未加引号字段名与单引号。
     */
    static <T> T parseJson(String content, Class<T> type) {
        String json = extractJsonObject(content);
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException strict) {
            try {
                T value = LENIENT_MAPPER.readValue(json, type);
                log.info("[drama-text] JSON 经容错解析成功 type={}", type.getSimpleName());
                return value;
            } catch (JsonProcessingException ignored) {
                return null;
            }
        }
    }

    /**
     * 合同修复：场次号按位置定序、空场次丢弃、null 集合归零、空白人名剔除。
     * 模型输出是外部输入，落库前必须过这一道。
     */
    private static ScriptDoc normalize(ScriptDoc doc) {
        if (doc == null || doc.getScenes() == null || doc.getScenes().isEmpty()) {
            return null;
        }
        List<ScriptDoc.Scene> kept = new ArrayList<>();
        for (ScriptDoc.Scene scene : doc.getScenes()) {
            if (scene == null) {
                continue;
            }
            scene.setIndex(kept.size() + 1);
            scene.setCharacters(cleanNames(scene.getCharacters()));
            scene.setProps(cleanMainProps(scene.getProps()));
            List<ScriptDoc.Dialogue> dialogue = new ArrayList<>();
            for (ScriptDoc.Dialogue line : safe(scene.getDialogue())) {
                if (line == null || !StringUtils.hasText(line.getLine())) {
                    continue;
                }
                dialogue.add(line);
            }
            scene.setDialogue(dialogue);
            kept.add(scene);
        }
        if (kept.isEmpty()) {
            return null;
        }
        doc.setScenes(kept);
        return doc;
    }

    private static List<String> cleanNames(List<String> names) {
        List<String> clean = new ArrayList<>();
        for (String name : safe(names)) {
            if (StringUtils.hasText(name)) {
                clean.add(name.strip());
            }
        }
        return clean;
    }

    private static List<String> cleanMainProps(List<String> names) {
        List<String> clean = new ArrayList<>();
        for (String name : safe(names)) {
            if (StringUtils.hasText(name)) {
                String value = name.strip();
                if (!clean.contains(value)) {
                    clean.add(value);
                }
                if (clean.size() >= MAX_MAIN_PROPS_PER_SCENE) {
                    break;
                }
            }
        }
        return clean;
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static <T> T readList(String content, Class<T> type) {
        return parseJson(content, type);
    }

    static AssetDraftList normalizeAssets(AssetDraftList list) {
        if (list == null || list.getAssets() == null) {
            return null;
        }
        Map<String, AssetDraftList.AssetDraft> unique = new LinkedHashMap<>();
        for (AssetDraftList.AssetDraft draft : list.getAssets()) {
            if (draft == null || !StringUtils.hasText(draft.getName()) || !StringUtils.hasText(draft.getType())) {
                continue;
            }
            String name = draft.getName().strip();
            String tag = StringUtils.hasText(draft.getStateTag())
                    ? draft.getStateTag().strip()
                    : DramaAssetDrafts.inferStateTag(name);
            draft.setName(name);
            draft.setType(draft.getType().strip());
            draft.setStateTag(tag);
            // 入梦/换装态必须独立且 must_split，模型标错就按同一套词表纠正。
            draft.setMustSplit(!DramaContract.STATE_TAG_BASE.equals(tag));
            if (!StringUtils.hasText(draft.getIdentityInvariants())) {
                draft.setNeedsHuman(true);
            }
            unique.putIfAbsent(draft.getType() + "\0" + name + "\0" + tag, draft);
        }
        if (unique.isEmpty()) {
            return null;
        }
        list.setAssets(new ArrayList<>(unique.values()));
        return list;
    }

    static ShotDraftList normalizeShots(ShotDraftList list, ScriptDoc script, List<String> assetNames) {
        if (list == null || list.getShots() == null || list.getShots().isEmpty()) {
            return null;
        }
        int sceneCount = script == null || script.getScenes() == null ? 0 : script.getScenes().size();
        Set<String> allowed = new LinkedHashSet<>(assetNames);
        List<ShotDraftList.ShotDraft> raw = new ArrayList<>();
        int lastChapter = 1;
        for (ShotDraftList.ShotDraft shot : list.getShots()) {
            if (shot == null || !StringUtils.hasText(shot.getTitle())) {
                continue;
            }
            if (shot.getChapterIndex() == null) {
                shot.setChapterIndex(lastChapter);
            } else {
                lastChapter = shot.getChapterIndex();
            }
            raw.add(shot);
        }
        if (raw.isEmpty()) {
            return null;
        }
        raw.sort(Comparator
                .comparingInt((ShotDraftList.ShotDraft shot) -> clampChapter(shot.getChapterIndex(), sceneCount))
                .thenComparingInt(shot -> shot.getIndex() == null ? Integer.MAX_VALUE : shot.getIndex()));
        Map<Integer, Integer> perChapter = new HashMap<>();
        List<ShotDraftList.ShotDraft> kept = new ArrayList<>();
        for (ShotDraftList.ShotDraft shot : raw) {
            int chapter = clampChapter(shot.getChapterIndex(), sceneCount);
            shot.setChapterIndex(chapter);
            shot.setIndex(perChapter.merge(chapter, 1, Integer::sum));
            shot.setTitle(shot.getTitle().strip());
            shot.setCharacterNames(retain(shot.getCharacterNames(), allowed));
            shot.setPropNames(retain(shot.getPropNames(), allowed));
            if (!allowed.contains(shot.getLocationName())) {
                shot.setLocationName(null);
            }
            shot.setCastCount(shot.getCharacterNames().size());
            kept.add(shot);
        }
        list.setShots(kept);
        if (list.getChapters() == null || list.getChapters().isEmpty()) {
            list.setChapters(chaptersFrom(script));
        }
        return list;
    }

    private static int clampChapter(Integer chapterIndex, int sceneCount) {
        int chapter = chapterIndex == null || chapterIndex < 1 ? 1 : chapterIndex;
        if (sceneCount > 0 && chapter > sceneCount) {
            return sceneCount;
        }
        return chapter;
    }

    static ShotDraftList clampDurations(ShotDraftList list, int fallbackSec) {
        if (list == null || list.getShots() == null) {
            return list;
        }
        int fallback = DramaDurations.clamp(fallbackSec);
        for (ShotDraftList.ShotDraft shot : list.getShots()) {
            if (shot == null) {
                continue;
            }
            shot.setDurationSec(DramaDurations.clamp(
                    shot.getDurationSec() == null ? fallback : shot.getDurationSec()));
        }
        return list;
    }

    private static List<String> retain(List<String> names, Set<String> allowed) {
        List<String> kept = new ArrayList<>();
        for (String name : safe(names)) {
            if (StringUtils.hasText(name)) {
                String clean = name.strip();
                if (allowed.contains(clean) && !kept.contains(clean)) {
                    kept.add(clean);
                }
            }
        }
        return kept;
    }

    private static List<ShotDraftList.ChapterDraft> chaptersFrom(ScriptDoc script) {
        List<ShotDraftList.ChapterDraft> chapters = new ArrayList<>();
        if (script == null) {
            return chapters;
        }
        for (ScriptDoc.Scene scene : safe(script.getScenes())) {
            ShotDraftList.ChapterDraft chapter = new ShotDraftList.ChapterDraft();
            chapter.setIndex(scene.getIndex());
            chapter.setSceneIndex(scene.getIndex());
            chapter.setName(StringUtils.hasText(scene.getHeading())
                    ? scene.getHeading().strip()
                    : "第" + scene.getIndex() + "场");
            chapters.add(chapter);
        }
        return chapters;
    }

    private ScriptDoc record(long seriesId, String engine, ScriptDoc doc) {
        PARSED_SERIES.set(seriesId);
        PARSED_ENGINE.set(engine);
        log.info("[drama-text] 分场完成 seriesId={} engine={} scenes={}",
                seriesId, engine, doc.getScenes().size());
        return doc;
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
