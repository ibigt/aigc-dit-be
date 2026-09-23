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
 * 资产页自动提示词模板。多数风格共用二次元漫画风的同一骨架，只替换风格句；
 * 其它风格可在服装句后追加一句服饰/材质。
 * {@link DramaStyles#LIVE_REALISM} 角色图单独弱化大脸设定表排版（降低 Seedance 真人拒审概率），
 * 仅影响之后新生成的 {@code imagePrompt}；已锁定旧图需用户重抽才换像素。
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
            String stateTag = StringUtils.hasText(draft.getStateTag())
                    ? draft.getStateTag()
                    : inferStateTag(draft.getName());
            if (!StringUtils.hasText(draft.getIdentityInvariants())) {
                boolean mustSplit = !DramaContract.STATE_TAG_BASE.equals(stateTag);
                draft.setIdentityInvariants(mustSplit
                        ? "与主身份同一张脸和发型；禁止写成另一个人"
                        : draft.getName().trim() + "的外貌、发型、服装和年龄必须保持一致");
            }
            if (StringUtils.hasText(draft.getImagePrompt())
                    && !shouldReplaceImagePrompt(draft.getImagePrompt(), draft.getType(), specKey(style))) {
                continue;
            }
            Context context = contexts.get(uniqueKey(draft.getType(), draft.getName().trim(), stateTag));
            draft.setImagePrompt(buildPrompt(
                    draft.getType(), draft.getName().trim(), stateTag, style, context, draft.getIdentityInvariants()));
        }
    }

    public static String buildPrompt(String type, String name, DramaStyles.Spec style) {
        return buildPrompt(type, name, inferStateTag(name), style, null, null);
    }

    static String buildPrompt(String type, String name, String stateTag, DramaStyles.Spec style, Context context) {
        return buildPrompt(type, name, stateTag, style, context, null);
    }

    static String buildPrompt(String type, String name, String stateTag, DramaStyles.Spec style, Context context,
                              String identity) {
        DramaStyles.Spec spec = style == null ? DramaStyles.require(DramaStyles.ANIME_COMIC) : style;
        String styleLine = spec.prefix();
        if (DramaContract.ASSET_TYPE_SCENE.equals(type)) {
            return scenePrompt(name, styleLine, context);
        }
        if (DramaContract.ASSET_TYPE_PROP.equals(type)) {
            return propPrompt(name, styleLine);
        }
        return characterPrompt(name, stateTag, spec, identity);
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
        String prompt = buildPrompt(type, name, stateTag, style, context, null);
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

    private static String characterPrompt(String name, String stateTag, DramaStyles.Spec spec, String identity) {
        StringBuilder text = new StringBuilder();
        text.append(name).append("角色设定图。");
        text.append(name).append("单独站立，全身可见。");
        text.append("发型、脸型、五官、肤色、身材比例完整、可重复。");
        text.append("服装款式、颜色、面料、纹理、配饰和鞋履清晰。");
        String look = styleLookExtra(spec);
        if (StringUtils.hasText(look)) {
            text.append(look);
        }
        if (DramaContract.STATE_TAG_DREAM.equals(stateTag)) {
            text.append("入梦/梦境独立状态，光影可抽离漂浮，仍是同一张脸和发型，禁止写成另一个人。");
        } else if (DramaContract.STATE_TAG_COSTUME.equals(stateTag)) {
            text.append("换装/湿身等状态独立成图，脸与发型与主身份一致。");
        }
        // LIVE_REALISM：强制统一设定表拼版（对齐常见国漫设定图），但半身用 3/4 侧脸，避免正面大头照触发 Seedance。
        if (DramaStyles.LIVE_REALISM.equals(spec.key())) {
            text.append("同一张图必须做成固定四格设定拼版，不得只出单人全身单帧：");
            text.append("左侧一张 3/4 侧脸或 3/4 半身肖像（看清发型五官与服装领口），");
            text.append("右侧全身三视图站立（正面、侧面、背面）横排对齐。");
            text.append("禁止正面大头照、禁止正脸证件照特写、禁止摄影棚大脸特写拼版。");
        } else {
            text.append("同一张图内三视图排列：正面全身、侧面半身、面部特写。");
        }
        text.append("浅灰或纯白干净背景。");
        text.append("只要这一个角色，不要剧情场面，不要其他人物，不要漫画分镜格子，不要大段文字说明，不要配件拆解图。");
        // 写实风把设定摘要写入生图句，避免只留在 identity 字段时模型自由发挥成古装。
        if (DramaStyles.LIVE_REALISM.equals(spec.key()) && StringUtils.hasText(identity)) {
            text.append("外形与服装必须符合：").append(identity.trim()).append("。");
        }
        text.append(spec.prefix()).append("。");
        text.append("同一角色在不同提示词中，外貌、发型、服装和年龄必须保持一致。");
        return text.toString();
    }

    /**
     * 二次元漫画风不加句。其它风格只追加一句服饰/材质；写实风强调半写实 CG 设定立绘，弱化证件照/写真措辞。
     */
    static String styleLookExtra(DramaStyles.Spec spec) {
        if (spec == null) {
            return "";
        }
        return switch (spec.key()) {
            case DramaStyles.LIVE_REALISM -> "半写实 CG 角色设定立绘、清晰五官与服装材质，3D国漫轻写实厚涂，现代都市常服与当代发型妆造，禁止古装武侠汉服仙侠与古代发冠配饰，非摄影棚证件照、非真人照片质感。";
            case DramaStyles.ANIME_WUXIA -> "古装武侠人物，服饰飘逸，可佩刀剑，不要现代西装夹克或运动鞋。";
            case DramaStyles.COSTUME_COMIC -> "古风汉服人物，广袖裙裾与发饰清晰，不要现代西装夹克或运动鞋。";
            case DramaStyles.REPUBLICAN_ERA -> "民国人物，旗袍长衫或洋装礼帽，面料与纹样清晰，不要古装广袖。";
            case DramaStyles.CYBERPUNK -> "机能外套与科技面料，可有义体接口，不要古装汉服。";
            case DramaStyles.SCI_FI -> "科幻制服或轻型防护装，金属与合成面料清晰，不要古装。";
            case DramaStyles.RURAL -> "朴素布衣、粗布棉麻与日常劳作装，不要华服礼服或古装。";
            default -> "";
        };
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
            // 故意不用剧本 action：那是人物动作与剧情事件，会污染场景设定图。
        }
        text.append("只描述可固定复用的空间环境，不要写人物动作、对白或剧情事件。");
        text.append("建筑结构、空间布局、环境陈设和关键道具交代清楚。");
        text.append("墙面、地面、家具、金属、玻璃、木材等材质细节可见。");
        text.append("主光源、辅光、环境光、明暗关系、色温和阴影方向明确；可用冷暖光、高对比或丁达尔光营造空间情绪。");
        text.append("电影分镜视角，如广角全景、低机位、俯拍或对称构图。");
        text.append(styleLine).append("。");
        text.append("场景概念图，画面中不要出现人物。");
        return text.toString();
    }

    private static String propPrompt(String name, String styleLine) {
        StringBuilder text = new StringBuilder();
        text.append(name).append("道具设定图。");
        text.append("完整展示外形、材质、磨损和标志性细节，可被手持或放置。");
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

    /**
     * 仅替换空值、过短 stub、已知残句和旧规则模板；完整长句（含用户手改）一律保留。
     */
    public static boolean shouldReplaceImagePrompt(String prompt) {
        return shouldReplaceImagePrompt(prompt, null, null);
    }

    /**
     * @param type     资产类型；写实风仅对角色识别「三视图+面部特写」旧骨架
     * @param styleKey 项目风格；非 {@link DramaStyles#LIVE_REALISM} 时不因面部特写句失效
     */
    public static boolean shouldReplaceImagePrompt(String prompt, String type, String styleKey) {
        if (!StringUtils.hasText(prompt)) {
            return true;
        }
        if (prompt.length() < 80) {
            return true;
        }
        if (isLegacyOneLiner(prompt)) {
            return true;
        }
        if (prompt.contains("浅灰背景的角色三视图")
                || prompt.contains("浅灰或纯白背景的角色三视图")) {
            return true;
        }
        if (prompt.contains("角色设定表排版")
                || prompt.contains("符合剧情设定的核心动作")
                || prompt.contains("首次出场：")
                || prompt.contains("剧情用途：")) {
            return true;
        }
        // 旧场景模板把剧本 action 塞进「整体氛围」，会带入人物动作与剧情。
        if (isObsoleteScenePromptWithPlotAction(prompt, type)) {
            return true;
        }
        // 写实风旧角色设定表（白底三视图含面部特写）在打开资产页 / 生图时自动换成新骨架，无需手改库。
        return isObsoleteLiveRealismCharacterSheet(prompt, type, styleKey);
    }

    static boolean isObsoleteScenePromptWithPlotAction(String prompt, String type) {
        if (!StringUtils.hasText(prompt)) {
            return false;
        }
        if (StringUtils.hasText(type) && !DramaContract.ASSET_TYPE_SCENE.equals(type)) {
            return false;
        }
        return prompt.contains("场景概念图")
                && prompt.contains("整体氛围：")
                && prompt.contains("建筑结构、空间布局");
    }

    static boolean isObsoleteLiveRealismCharacterSheet(String prompt, String type, String styleKey) {
        if (!DramaStyles.LIVE_REALISM.equals(styleKey) || !StringUtils.hasText(prompt)) {
            return false;
        }
        if (StringUtils.hasText(type) && !DramaContract.ASSET_TYPE_CHARACTER.equals(type)) {
            return false;
        }
        // 已是含现代都市时代锁的新版固定四格则保留。
        if (prompt.contains("固定四格设定拼版")
                && prompt.contains("禁止正面大头照")
                && prompt.contains("现代都市常服")) {
            return false;
        }
        // 旧：三视图+正面面部特写；软版单帧；或已四格但仍缺现代都市时代锁。
        return prompt.contains("同一张图内三视图排列：正面全身、侧面半身、面部特写")
                || prompt.contains("单人 3/4 或正面全身为主")
                || prompt.contains("不要大脸特写拼版")
                || (prompt.contains("固定四格设定拼版") && !prompt.contains("现代都市常服"));
    }

    private static String specKey(DramaStyles.Spec style) {
        return style == null ? null : style.key();
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
