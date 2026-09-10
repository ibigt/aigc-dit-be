package cn.aigc.dit.server.drama;

import cn.aigc.dit.server.drama.text.AssetDraftList;
import cn.aigc.dit.server.drama.text.ScriptDoc;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 乙未合入时的规则草稿，以及资产页自动提示词模板。
 * 风格句来自项目 {@code style_key}（当前仅二次元漫画风），规格按类型固定：角色三视图设定表、场景无人概念图。
 */
public final class DramaAssetDrafts {

    private DramaAssetDrafts() {
    }

    public static AssetDraftList fromScript(ScriptDoc doc) {
        return fromScript(doc, DramaStyles.require(DramaStyles.ANIME_COMIC));
    }

    public static AssetDraftList fromScript(ScriptDoc doc, DramaStyles.Spec style) {
        AssetDraftList list = new AssetDraftList();
        if (doc == null || doc.getScenes() == null) {
            return list;
        }
        Map<String, Context> contexts = collectContext(doc);
        Map<String, AssetDraftList.AssetDraft> unique = new LinkedHashMap<>();
        for (ScriptDoc.Scene scene : doc.getScenes()) {
            if (scene == null) {
                continue;
            }
            addNames(unique, scene.getCharacters(), DramaContract.ASSET_TYPE_CHARACTER, style, contexts);
            addNames(unique, scene.getProps(), DramaContract.ASSET_TYPE_PROP, style, contexts);
            if (StringUtils.hasText(scene.getLocation())) {
                addName(unique, scene.getLocation(), DramaContract.ASSET_TYPE_SCENE, style, contexts);
            }
        }
        list.setAssets(new ArrayList<>(unique.values()));
        return list;
    }

    /**
     * 乙抽出的草稿若未带提示词，用同一套模板补上，不覆盖已有长 prompt。
     */
    public static void ensurePrompts(AssetDraftList list, ScriptDoc doc, DramaStyles.Spec style) {
        if (list == null || list.getAssets() == null) {
            return;
        }
        Map<String, Context> contexts = collectContext(doc);
        for (AssetDraftList.AssetDraft draft : list.getAssets()) {
            if (draft == null || !StringUtils.hasText(draft.getName()) || !StringUtils.hasText(draft.getType())) {
                continue;
            }
            if (StringUtils.hasText(draft.getImagePrompt()) && !isLegacyOneLiner(draft.getImagePrompt())) {
                continue;
            }
            String stateTag = StringUtils.hasText(draft.getStateTag())
                    ? draft.getStateTag()
                    : inferStateTag(draft.getName());
            Context context = contexts.get(uniqueKey(draft.getType(), draft.getName().trim(), stateTag));
            draft.setImagePrompt(buildPrompt(draft.getType(), draft.getName().trim(), stateTag, style, context));
        }
    }

    public static String buildPrompt(String type, String name, DramaStyles.Spec style) {
        return buildPrompt(type, name, inferStateTag(name), style, null);
    }

    static String buildPrompt(String type, String name, String stateTag, DramaStyles.Spec style, Context context) {
        DramaStyles.Spec spec = style == null ? DramaStyles.require(DramaStyles.ANIME_COMIC) : style;
        String styleLine = spec.prefix();
        if (DramaContract.ASSET_TYPE_SCENE.equals(type)) {
            return scenePrompt(name, styleLine, context);
        }
        if (DramaContract.ASSET_TYPE_PROP.equals(type)) {
            return propPrompt(name, styleLine, context);
        }
        return characterPrompt(name, stateTag, styleLine, context);
    }

    private static void addNames(Map<String, AssetDraftList.AssetDraft> unique, List<String> names, String type,
                                 DramaStyles.Spec style, Map<String, Context> contexts) {
        if (names == null) {
            return;
        }
        for (String name : names) {
            addName(unique, name, type, style, contexts);
        }
    }

    private static void addName(Map<String, AssetDraftList.AssetDraft> unique, String rawName, String type,
                                DramaStyles.Spec style, Map<String, Context> contexts) {
        if (!StringUtils.hasText(rawName)) {
            return;
        }
        String name = rawName.trim();
        String stateTag = inferStateTag(name);
        boolean mustSplit = !DramaContract.STATE_TAG_BASE.equals(stateTag);
        String key = uniqueKey(type, name, stateTag);
        if (unique.containsKey(key)) {
            return;
        }
        Context context = contexts.get(key);
        String prompt = buildPrompt(type, name, stateTag, style, context);
        AssetDraftList.AssetDraft draft = new AssetDraftList.AssetDraft();
        draft.setName(name);
        draft.setType(type);
        draft.setStateTag(stateTag);
        draft.setMustSplit(mustSplit);
        draft.setNeedsHuman(true);
        draft.setDescription(prompt);
        draft.setIdentityInvariants(mustSplit
                ? "与主身份同一张脸和发型；禁止写成另一个人"
                : name + "的外貌、发型、服装和年龄必须保持一致");
        draft.setImagePrompt(prompt);
        unique.put(key, draft);
    }

    private static String characterPrompt(String name, String stateTag, String styleLine, Context context) {
        StringBuilder text = new StringBuilder();
        text.append(name).append("角色设定图。");
        text.append(name).append("，单独出场，写明年龄、身份和性格气质。");
        text.append("发型、脸型、五官、肤色、身材比例等面部与外貌特征完整、可重复。");
        text.append("服装款式、颜色、面料、纹理、配饰和鞋履细节清晰可见。");
        if (DramaContract.STATE_TAG_DREAM.equals(stateTag)) {
            text.append("入梦/梦境独立状态，光影可抽离漂浮，仍是同一张脸和发型，禁止写成另一个人。");
        } else if (DramaContract.STATE_TAG_COSTUME.equals(stateTag)) {
            text.append("换装/湿身等状态独立成图，脸与发型与主身份一致。");
        } else {
            text.append("符合剧情设定的核心动作和人物状态");
            if (context != null && StringUtils.hasText(context.action())) {
                text.append("：").append(clip(context.action(), 80));
            }
            text.append("。");
        }
        if (context != null && StringUtils.hasText(context.heading())) {
            text.append("首次出场：").append(clip(context.heading(), 40)).append("。");
        }
        text.append("正面、侧面、背面三视图；全身远景，完整展示人物与服装；角色设定表排版。");
        text.append("纯白色干净背景。");
        text.append(styleLine).append("。");
        text.append("同一角色在不同提示词中，外貌、发型、服装和年龄必须保持一致。");
        return text.toString();
    }

    private static String scenePrompt(String name, String styleLine, Context context) {
        StringBuilder text = new StringBuilder();
        text.append(name).append("场景概念图。");
        text.append("场景名称：").append(name).append("。");
        if (context != null) {
            if (StringUtils.hasText(context.time()) || StringUtils.hasText(context.heading())) {
                text.append("时间与地点：");
                if (StringUtils.hasText(context.time())) {
                    text.append(context.time());
                }
                if (Boolean.TRUE.equals(context.interior())) {
                    text.append(" 内");
                } else if (Boolean.FALSE.equals(context.interior())) {
                    text.append(" 外");
                }
                if (StringUtils.hasText(context.heading())) {
                    text.append("，").append(clip(context.heading(), 40));
                }
                text.append("。");
            }
            if (StringUtils.hasText(context.action())) {
                text.append("整体氛围：").append(clip(context.action(), 80)).append("。");
            }
        }
        text.append("建筑结构、空间布局、环境陈设和关键道具交代清楚。");
        text.append("墙面、地面、家具、金属、玻璃、木材等材质细节可见。");
        text.append("主光源、辅光、环境光、明暗关系、色温和阴影方向明确；可用冷暖光、高对比或丁达尔光服务剧情情绪。");
        text.append("电影分镜视角，如广角全景、低机位、俯拍或对称构图。");
        text.append(styleLine).append("。");
        text.append("场景概念图，画面中不要出现人物。");
        return text.toString();
    }

    private static String propPrompt(String name, String styleLine, Context context) {
        StringBuilder text = new StringBuilder();
        text.append(name).append("道具设定图。");
        text.append("完整展示外形、材质、磨损和标志性细节，可被手持或放置。");
        if (context != null && StringUtils.hasText(context.action())) {
            text.append("剧情用途：").append(clip(context.action(), 60)).append("。");
        }
        text.append("正面与微距特写，纯色干净背景，静物产品级清晰。");
        text.append(styleLine).append("。");
        text.append("画面中不要出现人物。");
        return text.toString();
    }

    private static Map<String, Context> collectContext(ScriptDoc doc) {
        Map<String, Context> contexts = new LinkedHashMap<>();
        if (doc == null || doc.getScenes() == null) {
            return contexts;
        }
        for (ScriptDoc.Scene scene : doc.getScenes()) {
            if (scene == null) {
                continue;
            }
            Context bit = new Context(scene.getHeading(), scene.getTime(), scene.getInterior(), scene.getAction());
            remember(contexts, scene.getCharacters(), DramaContract.ASSET_TYPE_CHARACTER, bit);
            remember(contexts, scene.getProps(), DramaContract.ASSET_TYPE_PROP, bit);
            if (StringUtils.hasText(scene.getLocation())) {
                remember(contexts, List.of(scene.getLocation()), DramaContract.ASSET_TYPE_SCENE, bit);
            }
        }
        return contexts;
    }

    private static void remember(Map<String, Context> contexts, List<String> names, String type, Context bit) {
        if (names == null) {
            return;
        }
        for (String raw : names) {
            if (!StringUtils.hasText(raw)) {
                continue;
            }
            String name = raw.trim();
            String key = uniqueKey(type, name, inferStateTag(name));
            contexts.putIfAbsent(key, bit);
        }
    }

    private static boolean isLegacyOneLiner(String prompt) {
        return prompt != null && prompt.contains("中性纯色背景参考图") && prompt.length() < 80;
    }

    static String uniqueKey(String type, String name, String stateTag) {
        return type + "\0" + name + "\0" + (StringUtils.hasText(stateTag) ? stateTag : DramaContract.STATE_TAG_BASE);
    }

    public static String inferStateTag(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (name.contains("入梦") || name.contains("梦境") || name.contains("梦魇")
                || lower.contains("dream")) {
            return DramaContract.STATE_TAG_DREAM;
        }
        if (name.contains("换装") || name.contains("湿身") || lower.contains("costume")) {
            return DramaContract.STATE_TAG_COSTUME;
        }
        return DramaContract.STATE_TAG_BASE;
    }

    private static String clip(String value, int max) {
        String text = value.trim().replaceAll("\\s+", " ");
        return text.length() <= max ? text : text.substring(0, max);
    }

    record Context(String heading, String time, Boolean interior, String action) {
    }
}
