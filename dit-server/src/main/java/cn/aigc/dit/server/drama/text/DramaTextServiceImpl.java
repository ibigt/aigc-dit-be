package cn.aigc.dit.server.drama.text;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.server.drama.DramaAssetDrafts;
import cn.aigc.dit.server.drama.DramaContract;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 剧本理解层实现。按系列绑定的文本模型判定引擎；未绑定走规则分场，调用方无感。
 * <p>
 * 资产草稿与分镜草稿沿用 {@link DramaTextService} 的 default 规则实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DramaTextServiceImpl implements DramaTextService {

    /** 模型偶尔会多写键名；合同外的键一律忽略而不是让整次解析失败。 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final int LLM_JSON_RETRIES = 2;

    /**
     * 本线程最近一次 parseScript 的实际引擎。文本模型失败会静默回落规则，
     * 只用「决策谓词」报引擎会把回落也标成 llm，所以这里记下实际值供 {@link #engine(long)} 读取。
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
        return useLlm(seriesId) ? ENGINE_LLM : ENGINE_RULES;
    }

    @Override
    public ScriptDoc parseScript(long seriesId, String raw) {
        if (!useLlm(seriesId)) {
            return record(seriesId, ENGINE_RULES, ScriptParser.parse(raw));
        }
        ScriptDoc doc = null;
        try {
            doc = llmParse(seriesId, raw);
        } catch (RuntimeException e) {
            swallowUnlessUnbound(seriesId, "分场", e);
        }
        return doc == null
                ? record(seriesId, ENGINE_RULES, ScriptParser.parse(raw))
                : record(seriesId, ENGINE_LLM, doc);
    }

    @Override
    public AssetDraftList extractAssets(long seriesId, ScriptDoc script) {
        if (script == null || script.getScenes().isEmpty() || !useLlm(seriesId)) {
            return DramaTextService.super.extractAssets(seriesId, script);
        }
        AssetDraftList drafts = null;
        try {
            drafts = attemptJson(seriesId, DramaTextPrompts.assetSystem(),
                    DramaTextPrompts.assetUser(toJson(script)),
                    content -> normalizeAssets(readList(content, AssetDraftList.class)), "AssetDraftList");
        } catch (RuntimeException e) {
            swallowUnlessUnbound(seriesId, "资产抽取", e);
        }
        return drafts == null ? DramaTextService.super.extractAssets(seriesId, script) : drafts;
    }

    @Override
    public ShotDraftList draftShots(long seriesId, ScriptDoc script, List<String> assetNames) {
        if (script == null || script.getScenes().isEmpty() || !useLlm(seriesId)) {
            return DramaTextService.super.draftShots(seriesId, script, assetNames);
        }
        ShotDraftList drafts = null;
        try {
            drafts = attemptJson(seriesId, DramaTextPrompts.shotSystem(),
                    DramaTextPrompts.shotUser(toJson(script), String.join("、", safe(assetNames))),
                    content -> normalizeShots(readList(content, ShotDraftList.class), script, safe(assetNames)),
                    "ShotDraftList");
        } catch (RuntimeException e) {
            swallowUnlessUnbound(seriesId, "分镜草稿", e);
        }
        return drafts == null ? DramaTextService.super.draftShots(seriesId, script, assetNames) : drafts;
    }

    /**
     * 只认系列绑定，不认环境变量：{@code drama_series.text_model_id → ai_model}。
     * 用 {@code resolve} 而非 {@code llmAvailable}——后者把「绑了但已停用」压成 false，
     * 会让用户被静默降级成规则分场，而 {@link TextChatGateway#BINDING_UNUSABLE} 的意图是提示去换绑。
     */
    private boolean useLlm(long seriesId) {
        return gateway.resolve(seriesId).isPresent();
    }

    private ScriptDoc llmParse(long seriesId, String raw) {
        return attemptJson(seriesId, DramaTextPrompts.scriptSystem(), DramaTextPrompts.scriptUser(raw),
                DramaTextServiceImpl::readScript, "ScriptDoc");
    }

    /** 供三条 LLM 路径共用：换绑提示原样上抛，其余吞掉并由调用方回落规则。 */
    private void swallowUnlessUnbound(long seriesId, String what, RuntimeException e) {
        if (e instanceof BizException biz && TextChatGateway.BINDING_UNUSABLE.equals(biz.getMessage())) {
            throw biz;
        }
        log.warn("[drama-text] {} 走文本模型失败，回落规则 seriesId={}: {}", what, seriesId, e.toString());
    }

    private <T> T attemptJson(long seriesId, String system, String user,
                              java.util.function.Function<String, T> reader, String what) {
        TextChatRequest req = new TextChatRequest(system, user, true);
        for (int attempt = 1; attempt <= LLM_JSON_RETRIES; attempt++) {
            T value = reader.apply(gateway.chat(seriesId, req).getContent());
            if (value != null) {
                if (attempt > 1) {
                    log.info("[drama-text] {} 第 {} 次输出符合合同 seriesId={}", what, attempt, seriesId);
                }
                return value;
            }
            log.warn("[drama-text] {} 输出不符合合同 seriesId={} attempt={}", what, seriesId, attempt);
        }
        return null;
    }

    private String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    static ScriptDoc readScript(String content) {
        String json = extractJsonObject(content);
        if (json == null) {
            return null;
        }
        try {
            return normalize(MAPPER.readValue(json, ScriptDoc.class));
        } catch (JsonProcessingException e) {
            return null;
        }
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
            scene.setProps(cleanNames(scene.getProps()));
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

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static <T> T readList(String content, Class<T> type) {
        String json = extractJsonObject(content);
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            return null;
        }
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
        Set<String> allowed = new LinkedHashSet<>(assetNames);
        Map<Integer, Integer> perChapter = new HashMap<>();
        List<ShotDraftList.ShotDraft> kept = new ArrayList<>();
        for (ShotDraftList.ShotDraft shot : list.getShots()) {
            if (shot == null || !StringUtils.hasText(shot.getTitle())) {
                continue;
            }
            int chapter = shot.getChapterIndex() == null ? 1 : shot.getChapterIndex();
            shot.setChapterIndex(chapter);
            shot.setIndex(perChapter.merge(chapter, 1, Integer::sum));
            shot.setTitle(shot.getTitle().strip());
            Integer duration = shot.getDurationSec();
            shot.setDurationSec(duration != null && DramaContract.DURATIONS.contains(duration)
                    ? duration
                    : DramaContract.DEFAULT_DURATION_SEC);
            shot.setCharacterNames(retain(shot.getCharacterNames(), allowed));
            shot.setPropNames(retain(shot.getPropNames(), allowed));
            if (!allowed.contains(shot.getLocationName())) {
                shot.setLocationName(null);
            }
            shot.setCastCount(shot.getCharacterNames().size());
            kept.add(shot);
        }
        if (kept.isEmpty()) {
            return null;
        }
        list.setShots(kept);
        if (list.getChapters() == null || list.getChapters().isEmpty()) {
            list.setChapters(chaptersFrom(script));
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
}
