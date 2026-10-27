package cn.aigc.dit.server.drama;

import cn.aigc.dit.server.drama.text.AssetDraftList;
import cn.aigc.dit.server.drama.text.ScriptDoc;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 资产页自动提示词模板。按大类分流：
 * 动漫大类共用二次元漫画风的同一骨架，只替换风格句与服饰句；
 * 写实大类（真人写实 / 真人3D / 古风写实 / 霓虹赛博）参照真人写实风提取共用实拍级规则——
 * 角色为真人写真定妆拼版（真人3D 用更强的实拍定妆转面：正脸近景 + 正面、严格 90 度侧面、背面三个全身），
 * 场景为实拍空镜（窗户光或现场实用光、使用磨损、平视拍摄），道具为实拍静物；
 * 各风格再叠加题材服饰句。只换风格与构图，不写死某一个人的年龄、发型和服装。
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
            for (String location : sceneAssetLocations(scene.getLocation())) {
                addName(unique, location, DramaContract.ASSET_TYPE_SCENE, style, contexts);
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
            String identity = draft.getIdentityInvariants();
            if (!StringUtils.hasText(identity)
                    || isSkeletonIdentity(draft.getName(), identity)
                    || isCharacterIdentityMisapplied(draft.getType(), identity)) {
                // 骨架句 / 误套句：优先从短 description 提升；场景道具提升失败则留空，避免假「正在补全」。
                if (DramaContract.ASSET_TYPE_CHARACTER.equals(draft.getType())
                        && isAppearanceBlurb(draft.getDescription())) {
                    draft.setIdentityInvariants(draft.getDescription().trim());
                } else if (isSettingBlurb(draft.getType(), draft.getDescription())) {
                    draft.setIdentityInvariants(draft.getDescription().trim());
                } else if (DramaContract.ASSET_TYPE_CHARACTER.equals(draft.getType())) {
                    draft.setIdentityInvariants(defaultIdentity(draft.getType(), draft.getName(), stateTag));
                } else {
                    draft.setIdentityInvariants(null);
                }
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

    /**
     * 换装 / 入梦走图生图时的提示词：版式和未写到的外观锁在本体图上，只描述变化。
     */
    public static String derivativePrompt(String change) {
        return derivativePrompt(change, null);
    }

    /**
     * 延伸版图生图提示词。固定保持句 + 变化；可选注入基础版外形摘要。
     */
    public static String derivativePrompt(String change, String baseIdentity) {
        String delta = StringUtils.hasText(change) ? change.trim() : "外观按该状态变化，脸与发型不变";
        if (!delta.endsWith("。") && !delta.endsWith(".")) {
            delta = delta + "。";
        }
        StringBuilder text = new StringBuilder();
        text.append("保持人物面部特征、发型轮廓、身高体型与基础设定一致；仅按下列描述改变服装或状态。");
        text.append("保持原图的版式、构图、比例和背景不变。");
        if (StringUtils.hasText(baseIdentity) && !isSkeletonIdentity(null, baseIdentity)) {
            text.append("基础外形：").append(baseIdentity.trim()).append("。");
        }
        text.append("变化：").append(delta);
        return text.toString();
    }

    /**
     * 从衍生资产里取出相对本体的变化。整张设定图长句不能当变化，否则会把人重画一遍；
     * 本体的身份不变量说的是「这个人长什么样」，也不是变化，不能写进图生图提示词。
     */
    public static String derivativeChange(String name, String stateTag, String description, String identity) {
        if (isChangeFact(description)) {
            return description.trim();
        }
        String state = StringUtils.hasText(stateTag) ? stateTag : inferStateTag(name);
        String label = stateNameOf(name);
        if (DramaContract.STATE_TAG_DREAM.equals(state) || label.contains("入梦") || label.contains("梦境")) {
            return "光影抽离、略微漂浮，仍是同一个人";
        }
        if (label.contains("湿身")) {
            return "外衣与发尾湿透，贴在身上";
        }
        if (DramaContract.STATE_TAG_COSTUME.equals(state) || label.contains("换装")) {
            return "服装换成这一状态下的另一套，脸与发型不变";
        }
        if (label.contains("战损") || label.contains("受伤")) {
            return "衣服破损带伤痕与污渍，其余与原图一致";
        }
        return "外观按该状态变化，脸与发型不变";
    }

    /**
     * 场次地点落成场景资产前拆开「甲/乙」。两边都像独立地名时各记一处，不再保留拼接名。
     * 有一边不像地名时整段保留，避免拆开本来就带斜杠的名字。只处理地点，不扫台词。
     */
    public static List<String> sceneAssetLocations(String location) {
        if (!StringUtils.hasText(location)) {
            return List.of();
        }
        String trimmed = location.trim();
        if (!trimmed.contains("/") && !trimmed.contains("／")) {
            return List.of(trimmed);
        }
        List<String> parts = new ArrayList<>();
        for (String raw : trimmed.split("[/／]")) {
            if (!StringUtils.hasText(raw)) {
                continue;
            }
            String word = raw.trim();
            if (SCENE_HEADING_MARKERS.contains(word)) {
                continue;
            }
            if (!isIndependentPlaceName(word) || parts.contains(word)) {
                if (!isIndependentPlaceName(word)) {
                    return List.of(trimmed);
                }
                continue;
            }
            parts.add(word);
        }
        if (parts.isEmpty()) {
            return List.of(trimmed);
        }
        return parts;
    }

    private static boolean isIndependentPlaceName(String word) {
        if (word.length() < 2) {
            return false;
        }
        for (int i = 0; i < word.length(); i++) {
            if (Character.UnicodeScript.of(word.charAt(i)) == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }

    private static final Set<String> SCENE_HEADING_MARKERS = Set.of(
            "内", "外", "内景", "外景", "室内", "室外",
            "日", "白天", "清晨", "晨", "上午", "中午", "下午", "傍晚", "黄昏", "夜", "晚上", "深夜", "凌晨");

    /** 「洛白·入梦」取本体名「洛白」。没有间隔号时返回原名。 */
    public static String baseCharacterName(String name) {
        if (!StringUtils.hasText(name)) {
            return "";
        }
        String trimmed = name.trim();
        int dot = trimmed.indexOf('·');
        if (dot < 0) {
            dot = trimmed.indexOf('・');
        }
        if (dot <= 0) {
            return trimmed;
        }
        return trimmed.substring(0, dot).trim();
    }

    /** 「洛白·入梦」取状态名「入梦」。没有后缀时返回空串。 */
    public static String stateNameOf(String name) {
        if (!StringUtils.hasText(name)) {
            return "";
        }
        String trimmed = name.trim();
        int dot = trimmed.indexOf('·');
        if (dot < 0) {
            dot = trimmed.indexOf('・');
        }
        if (dot <= 0 || dot >= trimmed.length() - 1) {
            return "";
        }
        return trimmed.substring(dot + 1).trim();
    }

    private static boolean isAppearanceFact(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String value = text.trim();
        return value.length() <= 200
                && !value.contains("角色设定图")
                && !value.contains("三视图")
                && !value.contains("设定拼版");
    }

    /**
     * 状态描述能否直接当作「相对本体的变化」写进图生图提示词。
     * 排除整张设定图模板，也排除身份锁定句（那是「这个人是谁」，不是变化）。
     */
    public static boolean isChangeFact(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String value = text.trim();
        return !value.contains("角色设定图")
                && !value.contains("三视图")
                && !value.contains("设定拼版")
                && !value.contains("角色设定表")
                && !value.contains("同一张脸")
                && !value.contains("必须保持一致")
                && !value.contains("禁止写成另一个人");
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
        if (DramaContract.ASSET_TYPE_SCENE.equals(type)) {
            return scenePrompt(name, spec, context, identity);
        }
        if (DramaContract.ASSET_TYPE_PROP.equals(type)) {
            return propPrompt(name, spec, identity);
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
        String raw = rawName.trim();
        if (DramaContract.ASSET_TYPE_CHARACTER.equals(type) && DramaDialogueSpeakers.isNarration(raw)) {
            return;
        }
        DramaAssetVersions.ParsedName parsed = DramaContract.ASSET_TYPE_CHARACTER.equals(type)
                ? DramaAssetVersions.parseLegacyName(raw)
                : new DramaAssetVersions.ParsedName(raw, "", DramaContract.STATE_TAG_BASE);
        String name = parsed.familyName();
        String stateTag = StringUtils.hasText(parsed.versionLabel())
                ? parsed.stateTag()
                : inferStateTag(raw);
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
        if (mustSplit && StringUtils.hasText(parsed.versionLabel())) {
            // 变化描述留给 LLM/用户；骨架先用标签占位短句。
            draft.setDescription(parsed.versionLabel());
            // 草稿暂无 versionLabel 字段；用 description 短标签 + stateTag 表达版本。
        } else {
            draft.setDescription(prompt);
        }
        // 场景/道具首屏不定骨架空话，等 LLM 写出真实设定；角色仍用外形占位句。
        draft.setIdentityInvariants(DramaContract.ASSET_TYPE_CHARACTER.equals(type)
                ? defaultIdentity(type, name, stateTag)
                : null);
        draft.setImagePrompt(prompt);
        unique.put(key, draft);
    }

    /**
     * 按资产类型生成抽取骨架的「设定摘要」占位句。角色才写外貌/发型/服装/年龄；
     * 场景/道具返回空，避免页面长期显示假「正在补全」。
     */
    public static String defaultIdentity(String type, String name, String stateTag) {
        if (DramaContract.ASSET_TYPE_SCENE.equals(type) || DramaContract.ASSET_TYPE_PROP.equals(type)) {
            return null;
        }
        String label = StringUtils.hasText(name) ? name.trim() : "";
        boolean mustSplit = StringUtils.hasText(stateTag) && !DramaContract.STATE_TAG_BASE.equals(stateTag);
        if (mustSplit) {
            return "与主身份同一张脸和发型；禁止写成另一个人";
        }
        return label.isEmpty()
                ? "外貌、发型、服装和年龄必须保持一致"
                : label + "的外貌、发型、服装和年龄必须保持一致";
    }

    /** 场景/道具误用了角色外形占位句。 */
    public static boolean isCharacterIdentityMisapplied(String type, String identity) {
        if (!StringUtils.hasText(identity)) {
            return false;
        }
        if (DramaContract.ASSET_TYPE_CHARACTER.equals(type) || !StringUtils.hasText(type)) {
            return false;
        }
        String text = identity.trim();
        return text.contains("外貌、发型、服装和年龄必须保持一致")
                || text.contains("与主身份同一张脸和发型");
    }

    private static String characterPrompt(String name, String stateTag, DramaStyles.Spec spec, String identity) {
        StringBuilder text = new StringBuilder();
        text.append(name).append("角色设定图。");
        text.append(name).append("单独站立，全身可见。");
        boolean hasIdentity = StringUtils.hasText(identity) && !isSkeletonIdentity(name, identity);
        // 已有外形设定时不再重复「发型服装须完整」通用句。
        if (!hasIdentity) {
            text.append("发型、脸型、五官、肤色、身材比例完整、可重复。");
            text.append("服装款式、颜色、面料、纹理、配饰和鞋履清晰。");
        }
        // styleLookExtra 只补题材服饰差；质感与禁止项统一放在 prefix。
        String look = styleLookExtra(spec);
        if (StringUtils.hasText(look)) {
            text.append(look);
        }
        if (DramaContract.STATE_TAG_DREAM.equals(stateTag)) {
            text.append("入梦/梦境独立状态，光影可抽离漂浮，仍是同一张脸和发型，禁止写成另一个人。");
        } else if (DramaContract.STATE_TAG_COSTUME.equals(stateTag)) {
            text.append("换装/湿身等状态独立成图，脸与发型与主身份一致。");
        } else if (DramaContract.STATE_TAG_WET.equals(stateTag)) {
            text.append("湿发/湿身独立状态：头发湿透贴服，衣物淋湿贴身，仍是同一张脸和发型，禁止写成另一个人。");
        } else if (DramaContract.STATE_TAG_CUSTOM.equals(stateTag)) {
            text.append("角色特殊状态独立成图，脸与发型与主身份保持一致。");
        }
        if (DramaStyles.LIVE_REALISM.equals(spec.key())) {
            // 现代写实：定妆转面压缩版；禁 CG/质感由 prefix 承担，此处只保留构图与白底。
            text.append("同一张图做成同一人的真人实拍定妆：左侧正脸近景（头肩，侧光单灯，看清毛孔发丝），");
            text.append("右侧正面、严格 90 度侧面、背面三个全身视图横排对齐，机位与比例一致。");
            text.append("不得只出单帧，不得拼不同人，侧面禁止用 3/4 侧脸代替。中老年须保留皱纹眼袋，禁止磨成青年脸。");
            text.append("纯白无缝背景，四视图同一白底；禁止房间窗户墙角与任何可读文字。");
        } else if (spec.realistic()) {
            // 古风写实 / 霓虹赛博：四格设定表；写真质感由 prefix 承担。
            text.append("真人写真定妆。同一张图必须做成固定四格设定拼版，不得只出单人全身单帧：");
            text.append("左侧一张 3/4 侧脸或 3/4 半身肖像（看清发型五官与服装领口），");
            text.append("右侧全身三视图站立（正面、侧面、背面）横排对齐。");
            text.append("禁止正面大头照与证件照特写。浅灰或纯白干净背景。");
        } else {
            text.append("同一张图内三视图排列：正面全身、侧面半身、面部特写。");
            text.append("浅灰或纯白干净背景。");
        }
        text.append("只要这一个角色，不要剧情场面，不要其他人物，不要漫画分镜格子，不要大段文字说明，不要配件拆解图。");
        if (hasIdentity) {
            text.append("外形与服装必须符合：").append(identity.trim()).append("。");
        }
        text.append(spec.prefix()).append("。");
        // 无外形设定时保留一致性收尾；有设定则外形块已覆盖，不再重复。
        if (!hasIdentity) {
            text.append("同一角色在不同提示词中，外貌、发型、服装和年龄必须保持一致。");
        }
        return text.toString();
    }

    /**
     * 服饰/题材附加句。写实质感与禁止项已在 {@link DramaStyles.Spec#prefix()}，此处只写题材差。
     */
    static String styleLookExtra(DramaStyles.Spec spec) {
        if (spec == null) {
            return "";
        }
        if (spec.realistic()) {
            return switch (spec.key()) {
                case DramaStyles.LIVE_REALISM -> "现代都市常服与当代发型妆造，禁止无故改成古装武侠汉服仙侠，禁止胸牌编号与可读文字。";
                case DramaStyles.GUOFENG_3D -> "国风古典妆造，汉服广袖与发冠金饰，禁止现代西装夹克与运动鞋。";
                case DramaStyles.NEON_CYBER -> "赛博都市发型妆造，机能外套与金属义体发光接口清晰可见，禁止古装汉服与证件照大平光。";
                case DramaStyles.SCI_FI_CINEMA -> "科幻电影妆造，科技面料制服与智能终端细节清晰，禁止古装汉服与现代运动便装。";
                case DramaStyles.AMERICAN_RETRO -> "美式复古妆造，复古牛仔、皮夹克或波点连衣裙与老式发型，禁止古装汉服与现代运动装。";
                default -> "现代都市常服与当代发型妆造，禁止古装武侠汉服仙侠与古代发冠配饰。";
            };
        }
        return switch (spec.key()) {
            case DramaStyles.ANIME_WUXIA -> "古装武侠人物，服饰飘逸，可佩刀剑，不要现代西装夹克或运动鞋。";
            case DramaStyles.COSTUME_COMIC -> "古风汉服人物，广袖裙裾与发饰清晰，不要现代西装夹克或运动鞋。";
            case DramaStyles.REPUBLICAN_ERA -> "民国人物，旗袍长衫或洋装礼帽，面料与纹样清晰，不要古装广袖。";
            case DramaStyles.CYBERPUNK -> "机能外套与科技面料，可有义体接口，不要古装汉服。";
            case DramaStyles.SCI_FI -> "科幻制服或轻型防护装，金属与合成面料清晰，不要古装。";
            case DramaStyles.RURAL -> "朴素布衣、粗布棉麻与日常劳作装，不要华服礼服或古装。";
            default -> "";
        };
    }

    private static String scenePrompt(String name, DramaStyles.Spec spec, Context context, String identity) {
        String styleLine = spec.prefix();
        boolean liveAction = spec.realistic();
        boolean hasSpace = isSpaceFact(identity);
        StringBuilder text = new StringBuilder();
        text.append(name).append(liveAction ? "实拍空镜。" : "场景概念图。");
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
        }
        text.append("只画可复用的空镜空间，不要人物、动作、对白或剧情。");
        if (hasSpace) {
            text.append("空间陈设必须符合：").append(identity.trim()).append("。");
        } else {
            text.append("交代建筑结构、布局、陈设与材质。");
        }
        if (liveAction) {
            text.append("窗户光或现场实用光，带使用磨损；平视实拍，勿加成阁楼海报墙或对称概念图。");
        } else {
            text.append("光影与色温明确，电影分镜视角。");
        }
        text.append(styleLine).append("。");
        text.append("画面中不要出现人物。");
        return text.toString();
    }

    private static String propPrompt(String name, DramaStyles.Spec spec, String identity) {
        String styleLine = spec.prefix();
        boolean hasFact = StringUtils.hasText(identity)
                && !isSkeletonIdentity(name, identity)
                && !isCharacterIdentityMisapplied(DramaContract.ASSET_TYPE_PROP, identity);
        StringBuilder text = new StringBuilder();
        if (spec.realistic()) {
            text.append(name).append("实拍静物。");
            text.append(hasFact
                    ? "按设定展示外形材质，真实相机拍摄，可手持或放置。"
                    : "真实相机拍摄，展示外形、材质、磨损与标志性细节，可手持或放置。");
            text.append("正面与微距特写，干净背景。");
            if (hasFact) {
                text.append("外形材质必须符合：").append(identity.trim()).append("。");
            }
            text.append(styleLine).append("。");
            text.append("画面中不要出现人物。");
            return text.toString();
        }
        text.append(name).append("道具设定图。");
        text.append(hasFact
                ? "按设定展示外形材质，可手持或放置。"
                : "展示外形、材质、磨损与标志性细节，可手持或放置。");
        text.append("正面与微距特写，纯色干净背景。");
        if (hasFact) {
            text.append("外形材质必须符合：").append(identity.trim()).append("。");
        }
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
            remember(contexts, sceneAssetLocations(scene.getLocation()), DramaContract.ASSET_TYPE_SCENE, bit);
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
     * 是否仍是抽取骨架的无效设定句（无可识别事实）。
     * 页面「外形/场景/道具设定」与二次增强覆盖都依赖此判断。
     */
    public static boolean isSkeletonIdentity(String name, String identity) {
        if (!StringUtils.hasText(identity)) {
            return true;
        }
        String text = identity.trim();
        if (text.contains("外貌、发型、服装和年龄必须保持一致")
                || text.contains("与主身份同一张脸和发型")
                || text.contains("空间结构、陈设与光影氛围必须保持一致")
                || text.contains("外形、材质与标志性细节必须保持一致")) {
            return true;
        }
        if (StringUtils.hasText(name)) {
            String label = name.trim();
            if (text.equals(label + "的外貌、发型、服装和年龄必须保持一致")
                    || text.equals(label + "的空间结构、陈设与光影氛围必须保持一致")
                    || text.equals(label + "的外形、材质与标志性细节必须保持一致")) {
                return true;
            }
        }
        return false;
    }

    /**
     * description 是否像角色外貌短句（而非设定图长模板），可提升为外形设定。
     */
    public static boolean isAppearanceBlurb(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String value = text.trim();
        if (value.length() > 220 || isSkeletonIdentity(null, value) || looksLikeImageTemplate(value)) {
            return false;
        }
        return true;
    }

    /**
     * description 是否像场景/道具可用的短设定（非生图长模板），可提升为设定摘要。
     */
    public static boolean isSettingBlurb(String type, String text) {
        if (!DramaContract.ASSET_TYPE_SCENE.equals(type) && !DramaContract.ASSET_TYPE_PROP.equals(type)) {
            return false;
        }
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String value = text.trim();
        if (value.length() > 220 || isSkeletonIdentity(null, value) || looksLikeImageTemplate(value)) {
            return false;
        }
        if (value.contains("外貌、发型、服装和年龄") || value.contains("同一张脸和发型")) {
            return false;
        }
        return true;
    }

    static boolean looksLikeImageTemplate(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String value = text.trim();
        return value.contains("角色设定图")
                || value.contains("三视图")
                || value.contains("固定四格设定拼版")
                || value.contains("场景概念图")
                || value.contains("实拍空镜")
                || value.contains("道具设定图")
                || value.contains("实拍静物")
                || value.contains("连续运动的电影场面")
                || value.contains("只要这一个角色，不要剧情场面");
    }

    /**
     * 仅替换空值、过短 stub、已知残句和旧规则模板；完整长句（含用户手改）一律保留。
     */
    public static boolean shouldReplaceImagePrompt(String prompt) {
        return shouldReplaceImagePrompt(prompt, null, null);
    }

    /**
     * @param type     资产类型；写实大类仅对角色识别「三视图+面部特写」旧骨架，并替换旧 CGI 角色句、场景概念图和道具设定图
     * @param styleKey 项目风格；非写实大类时不因面部特写句失效
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
        if (isObsoleteRealisticSceneOrProp(prompt, type, styleKey)) {
            return true;
        }
        // 写实风旧角色设定表（白底三视图含面部特写）在打开资产页 / 生图时自动换成新骨架，无需手改库。
        return isObsoleteLiveRealismCharacterSheet(prompt, type, styleKey);
    }

    /**
     * 写实大类（4 风格）的场景、道具若仍是概念图 / 设定图旧句，打开资产页或生图时整段重写成实拍空镜、实拍静物。
     */
    static boolean isObsoleteRealisticSceneOrProp(String prompt, String type, String styleKey) {
        if (!DramaStyles.isRealistic(styleKey) || !StringUtils.hasText(prompt)) {
            return false;
        }
        if (DramaContract.ASSET_TYPE_SCENE.equals(type)) {
            return prompt.contains("场景概念图")
                    || prompt.contains("广角全景")
                    || prompt.contains("对称构图")
                    || prompt.contains("低机位");
        }
        if (DramaContract.ASSET_TYPE_PROP.equals(type)) {
            return prompt.contains("道具设定图");
        }
        return false;
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

    /**
     * 压缩前的白底定妆长模板：逐视图说明 + 旧风格缀（窗户光/35mm/胶片等），与当前短骨架区分。
     */
    static boolean isVerboseLiveRealismTurnaround(String prompt) {
        if (!StringUtils.hasText(prompt)) {
            return false;
        }
        return prompt.contains("左侧旁边一张正脸近景")
                || prompt.contains("严格属于同一个人的正面")
                || prompt.contains("不是中性灰摄影棚")
                || prompt.contains("窗户光与现场实用光")
                || prompt.contains("35mm 电影镜头")
                || prompt.contains("轻微胶片颗粒")
                || prompt.contains("当代电影质感")
                || prompt.contains("正面：身体与面部正对镜头")
                || prompt.contains("鼻尖、下巴、胸部、髋部和脚尖")
                || prompt.contains("可见毛孔与独立发丝，真实相机拍摄");
    }

    static boolean isObsoleteLiveRealismCharacterSheet(String prompt, String type, String styleKey) {
        if (!DramaStyles.isRealistic(styleKey) || !StringUtils.hasText(prompt)) {
            return false;
        }
        if (StringUtils.hasText(type) && !DramaContract.ASSET_TYPE_CHARACTER.equals(type)) {
            return false;
        }
        if (DramaStyles.LIVE_REALISM.equals(styleKey)) {
            // 旧长版定妆转面（虽已白底+正脸近景）仍整段重写成压缩版。
            if (isVerboseLiveRealismTurnaround(prompt)) {
                return true;
            }
            // 新版压缩定妆转面：纯白无缝 + 正脸近景短句即可保留。
            if ((prompt.contains("纯白无缝背景") || prompt.contains("纯白无缝底"))
                    && prompt.contains("正脸近景")
                    && !prompt.contains("靠窗的朴素墙面")) {
                return false;
            }
            return prompt.contains("靠窗的朴素墙面")
                    || prompt.contains("真实房间一角")
                    || prompt.contains("中性灰色摄影棚")
                    || prompt.contains("真人实拍定妆")
                    || prompt.contains("影视级仿真人数字人")
                    || prompt.contains("photorealistic CGI")
                    || prompt.contains("影视级虚拟角色设定板")
                    || prompt.contains("固定四格设定拼版")
                    || prompt.contains("次世代数字人质感")
                    || prompt.contains("非真人照片质感")
                    || prompt.contains("禁止正面大头照")
                    || prompt.contains("同一张图内三视图排列：正面全身、侧面半身、面部特写")
                    || prompt.contains("单人 3/4 或正面全身为主")
                    || prompt.contains("不要大脸特写拼版");
        }
        // 写实大类其余（古风写实 / 霓虹赛博）共用旧逻辑：固定四格+真人写真定妆句视为新版。
        if (prompt.contains("固定四格设定拼版")
                && prompt.contains("禁止正面大头照")
                && prompt.contains("真人写真定妆")
                && !prompt.contains("二次元写实")
                && !prompt.contains("3D国漫轻写实")
                && !prompt.contains("半写实 CG")
                && !prompt.contains("非真人照片质感")) {
            return false;
        }
        // 旧：三视图+正面面部特写；软版单帧；半写实/国漫措辞；或已四格但仍缺写真定妆句。
        return prompt.contains("同一张图内三视图排列：正面全身、侧面半身、面部特写")
                || prompt.contains("单人 3/4 或正面全身为主")
                || prompt.contains("不要大脸特写拼版")
                || prompt.contains("半写实 CG")
                || prompt.contains("二次元写实")
                || prompt.contains("3D国漫轻写实")
                || prompt.contains("非真人照片质感")
                || (prompt.contains("固定四格设定拼版") && !prompt.contains("真人写真定妆"));
    }

    private static String specKey(DramaStyles.Spec style) {
        return style == null ? null : style.key();
    }

    private static boolean isSpaceFact(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String value = text.trim();
        return value.length() <= 200
                && !value.contains("场景概念图")
                && !value.contains("实拍空镜")
                && !value.contains("广角全景");
    }

    private static boolean isLegacyOneLiner(String prompt) {
        return prompt != null && prompt.contains("中性纯色背景参考图") && prompt.length() < 80;
    }

    static String uniqueKey(String type, String name, String stateTag) {
        return type + "\0" + name + "\0" + (StringUtils.hasText(stateTag) ? stateTag : DramaContract.STATE_TAG_BASE);
    }

    /**
     * 状态标签以资产名的状态后缀为准（「本体名·状态名」）：有后缀就是状态资产，
     * 后缀是已知状态词给专属标签，其余按特殊状态处理。没有后缀就是本体。
     */
    public static String inferStateTag(String name) {
        if (!StringUtils.hasText(name)) {
            return DramaContract.STATE_TAG_BASE;
        }
        String suffix = stateNameOf(name);
        if (StringUtils.hasText(suffix)) {
            String lower = suffix.toLowerCase(Locale.ROOT);
            if (suffix.contains("入梦") || suffix.contains("梦境") || suffix.contains("梦魇")
                    || lower.contains("dream")) {
                return DramaContract.STATE_TAG_DREAM;
            }
            if (suffix.contains("换装") || suffix.contains("湿身") || lower.contains("costume")) {
                return DramaContract.STATE_TAG_COSTUME;
            }
            return DramaContract.STATE_TAG_CUSTOM;
        }
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
