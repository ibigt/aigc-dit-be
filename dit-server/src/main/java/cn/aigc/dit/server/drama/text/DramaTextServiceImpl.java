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
            .configure(JsonParser.Feature.ALLOW_TRAILING_COMMA, true);

    private static final int LLM_JSON_RETRIES = 2;
    private static final int ASSET_ENRICHMENT_BATCH_SIZE = 4;
    static final int MAX_JSON_TOKENS = 32768;
    /** 单场只保留模型按重要性排序后的前三个核心道具，防止偶发越过提示词。 */
    private static final int MAX_MAIN_PROPS_PER_SCENE = 3;

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
        for (int from = 0; from < assets.size(); from += ASSET_ENRICHMENT_BATCH_SIZE) {
            int to = Math.min(from + ASSET_ENRICHMENT_BATCH_SIZE, assets.size());
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
        log.info("[drama-text] 资产增强完成 seriesId={} assets={} elapsedMs={}",
                seriesId, assets.size(), elapsedMs(started));
        return skeleton;
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
            ShotDraftList drafts = attemptJson(seriesId, DramaTextPrompts.shotSystem(),
                    DramaTextPrompts.shotUser(
                            toJson(script), String.join("、", safe(assetNames)), duration, episodeBudgetSec),
                    content -> clampDurations(
                            normalizeShots(readList(content, ShotDraftList.class), script, safe(assetNames)),
                            duration),
                    "ShotDraftList", 16384);
            int before = drafts.getShots() == null ? 0 : drafts.getShots().size();
            ShotDraftList result = ShotDraftMinimizer.minimize(drafts, duration);
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
        return attemptJson(seriesId, DramaTextPrompts.scriptSystem(), DramaTextPrompts.scriptUser(raw),
                DramaTextServiceImpl::readScript, "ScriptDoc", 16384);
    }

    private <T> T attemptJson(long seriesId, String system, String user,
                              java.util.function.Function<String, T> reader, String what, int maxTokens) {
        String requestUser = user;
        String lastReason = "输出未通过合同校验";
        int tokens = maxTokens;
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
            if (truncatedByLength(response.getFinishReason())) {
                reason = "输出被 max_tokens 截断（finish_reason=" + response.getFinishReason() + "）。" + reason;
                tokens = nextMaxTokens(tokens);
            }
            lastReason = reason;
            log.warn("[drama-text] {} 输出不符合合同 seriesId={} attempt={} reason={} model={} finishReason={} contentLength={} nextMaxTokens={}",
                    what, seriesId, attempt, reason, response.getModel(),
                    response.getFinishReason(), content == null ? 0 : content.length(), tokens);
            requestUser = DramaTextPrompts.repairUser(user, what, reason, content);
        }
        throw new BizException(cn.aigc.dit.common.exception.ErrorCode.BAD_REQUEST,
                "文本模型输出未通过 " + what + " 合同：" + lastReason);
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

    /** 模型可能包代码围栏或前后带解释文字；取最外层花括号。 */
    private static String extractJsonObject(String content) {
        if (!StringUtils.hasText(content)) {
            return null;
        }
        String text = content.strip().replaceAll("^```[a-zA-Z]*", "").replaceAll("```$", "").strip();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : null;
    }

    /**
     * 先严格解析；失败再容忍注释与尾逗号。不放宽合同字段，也不改写合法 JSON 的语义。
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
                log.info("[drama-text] JSON 经注释/尾逗号容错解析成功 type={}", type.getSimpleName());
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
