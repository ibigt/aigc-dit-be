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
            if (!StringUtils.hasText(draft.getIdentityInvariants())
                    || isSkeletonIdentity(draft.getName(), draft.getIdentityInvariants())) {
                // 骨架句不算有效外形；若 description 已是角色外貌短句则提升，否则空时再落骨架兜底。
                if (DramaContract.ASSET_TYPE_CHARACTER.equals(draft.getType())
                        && isAppearanceBlurb(draft.getDescription())) {
                    draft.setIdentityInvariants(draft.getDescription().trim());
                } else if (!StringUtils.hasText(draft.getIdentityInvariants())) {
                    boolean mustSplit = !DramaContract.STATE_TAG_BASE.equals(stateTag);
                    draft.setIdentityInvariants(mustSplit
                            ? "与主身份同一张脸和发型；禁止写成另一个人"
                            : draft.getName().trim() + "的外貌、发型、服装和年龄必须保持一致");
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
        String delta = StringUtils.hasText(change) ? change.trim() : "外观按该状态变化，脸与发型不变";
        if (!delta.endsWith("。") && !delta.endsWith(".")) {
            delta = delta + "。";
        }
        return "保持原图的版式、构图、比例和背景不变。"
                + "除下列变化外，面部、发型、体型及其余外观与原图一致。"
                + "变化：" + delta;
    }

    /**
     * 从衍生资产里取出相对本体的变化。整张设定图长句不能当变化，否则会把人重画一遍。
     */
    public static String derivativeChange(String name, String stateTag, String description, String identity) {
        if (isAppearanceFact(description)) {
            return description.trim();
        }
        if (isAppearanceFact(identity) && !identity.contains("同一张脸")) {
            return identity.trim();
        }
        String state = StringUtils.hasText(stateTag) ? stateTag : inferStateTag(name);
        String label = name == null ? "" : name;
        if (DramaContract.STATE_TAG_DREAM.equals(state) || label.contains("入梦") || label.contains("梦境")) {
            return "光影抽离、略微漂浮，仍是同一个人";
        }
        if (label.contains("湿身")) {
            return "外衣与发尾湿透，贴在身上";
        }
        if (DramaContract.STATE_TAG_COSTUME.equals(state)) {
            return "服装换成这一状态下的另一套，脸与发型不变";
        }
        return "外观按该状态变化，脸与发型不变";
    }

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
            return propPrompt(name, spec);
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
        if (DramaContract.ASSET_TYPE_CHARACTER.equals(type) && DramaDialogueSpeakers.isNarration(name)) {
            return;
        }
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
        if (DramaStyles.REALISTIC_3D.equals(spec.key())) {
            text.append("同一张图必须做成同一人的真人实拍定妆：左侧旁边一张正脸近景，右侧是严格属于同一个人的正面、严格 90 度侧面、背面三个全身视图。");
            text.append("正脸近景只取头部和肩部，面部正对镜头，侧光或单灯打在脸上，看清毛孔、皱纹、眼球反射和发丝，与三个全身是同一张脸。");
            text.append("禁止均匀大平光，禁止蜡像磨皮。中年或老年必须看见皱纹、眼袋和松弛皮肤，禁止磨成青年脸。");
            text.append("不得只出单人单帧，不得把不同人物拼在一起，不得用证件照替代全身视图。");
            text.append("正面：身体与面部正对镜头，自然站立，双臂轻微离开身体，完整显示全身。");
            text.append("侧面：严格 90 度侧视，鼻尖、下巴、胸部、髋部和脚尖方向一致，展示真实侧脸轮廓，禁止用四分之三侧脸代替正侧面。");
            text.append("背面：身体背对镜头，清楚展示发型后部、发饰与服装背面。");
            text.append("三个全身视图采用相同的摄影机高度、人物比例、焦距感和地面位置，人物之间留出间隔，头顶、手部、衣摆和双脚均完整显示。");
            text.append("背景必须是纯白无缝底，四个视图同一块白底。禁止房间、窗户、墙角、窗帘、地板和杂物。不是中性灰摄影棚，不是三维模型展示台。");
            text.append("禁止胸牌、警号、姓名牌、编号、水印、标志、英文标语和任何可读文字。");
            text.append("禁止 3D 渲染、CG 数字人、游戏角色、塑料皮肤、动漫卡通、夸张眼睛、不真实身体比例、不一致的五官发型服装、");
            text.append("多余肢体、多余或缺失手指、裁切手脚和头部、模糊、过曝、杂乱背景。");
        } else if (spec.realistic()) {
            // 写实大类共用：真人写真四格设定表，半身用 3/4 侧脸，避免正面大头照。
            text.append("同一张图必须做成固定四格设定拼版，不得只出单人全身单帧：");
            text.append("左侧一张 3/4 侧脸或 3/4 半身肖像（看清发型五官与服装领口），");
            text.append("右侧全身三视图站立（正面、侧面、背面）横排对齐。");
            text.append("禁止正面大头照、禁止正脸证件照特写、禁止摄影棚大脸特写拼版。");
            text.append("浅灰或纯白干净背景。");
        } else {
            text.append("同一张图内三视图排列：正面全身、侧面半身、面部特写。");
            text.append("浅灰或纯白干净背景。");
        }
        text.append("只要这一个角色，不要剧情场面，不要其他人物，不要漫画分镜格子，不要大段文字说明，不要配件拆解图。");
        // 外形摘要写入生图句：页面只编辑 identity，构图/风格模板由本方法附加。
        if (StringUtils.hasText(identity)) {
            text.append("外形与服装必须符合：").append(identity.trim()).append("。");
        }
        text.append(spec.prefix()).append("。");
        if (DramaStyles.REALISTIC_3D.equals(spec.key())) {
            text.append("角色图背景必须是纯白无缝底，不要把风格句里的窗户或房间画进画面。");
        }
        text.append("同一角色在不同提示词中，外貌、发型、服装和年龄必须保持一致。");
        return text.toString();
    }

    /** 写实大类底句（参照真人写实风提取）：定妆形态 + 真实相机 + 清晰五官与材质。 */
    static final String PHOTOREAL_LOOK_BASE =
            "真人写真定妆立绘，真实相机棚拍柔光，清晰五官与服装材质，";

    /** 写实大类收尾反卡通句。 */
    static final String PHOTOREAL_LOOK_SUFFIX = "，禁止二次元卡通与3D国漫厚涂。";

    /**
     * 二次元漫画风不加句。其它风格追加一句服饰/材质。
     * 写实大类共用底句 + 各自题材服饰句；真人3D 在写实基础上用更强的实拍定妆句。
     */
    static String styleLookExtra(DramaStyles.Spec spec) {
        if (spec == null) {
            return "";
        }
        if (spec.realistic()) {
            return switch (spec.key()) {
                case DramaStyles.REALISTIC_3D -> "真人实拍定妆，可见毛孔与独立发丝，真实相机拍摄，侧光或单灯打在脸上，纯白无缝背景，禁止均匀大平光、房间、窗户、墙角、3D 渲染、CG 数字人、游戏角色、塑料皮肤与蜡像磨皮。服装、发型与年龄以该角色自身设定为准，禁止无故改成古装武侠汉服仙侠，禁止胸牌、编号和可读文字。";
                case DramaStyles.GUOFENG_3D -> PHOTOREAL_LOOK_BASE
                        + "国风古典妆造，汉服广袖与发冠金饰，禁止现代西装夹克与运动鞋"
                        + PHOTOREAL_LOOK_SUFFIX;
                case DramaStyles.NEON_CYBER -> PHOTOREAL_LOOK_BASE
                        + "赛博都市发型妆造，机能外套与金属义体发光接口清晰可见，禁止古装汉服，禁止证件照大平光"
                        + PHOTOREAL_LOOK_SUFFIX;
                default -> PHOTOREAL_LOOK_BASE
                        + "现代都市常服与当代发型妆造，禁止古装武侠汉服仙侠与古代发冠配饰"
                        + PHOTOREAL_LOOK_SUFFIX;
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
        // 写实大类共用实拍空镜规则；动漫大类走概念图分支。
        boolean liveAction = spec.realistic();
        StringBuilder text = new StringBuilder();
        text.append(name).append(liveAction ? "实拍空镜。" : "场景概念图。");
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
        if (liveAction) {
            text.append("窗户光或现场实用光打进这间房，墙面、地面和家具有使用过的磨损。");
            text.append("只画场景名称和剧本已经写出的空间，不要自行加成阁楼、夹层、品牌墙、海报墙或英文标语，不要画成对称的概念效果图。");
            text.append("真实相机平视拍摄的空镜，像站在房间里看，不要英雄式广角，不要俯拍。");
            if (isSpaceFact(identity)) {
                text.append("空间陈设必须符合：").append(identity.trim()).append("。");
            }
        } else {
            text.append("主光源、辅光、环境光、明暗关系、色温和阴影方向明确；可用冷暖光、高对比或丁达尔光营造空间情绪。");
            text.append("电影分镜视角，如广角全景、低机位、俯拍或对称构图。");
        }
        text.append(styleLine).append("。");
        text.append(liveAction ? "实拍空镜，画面中不要出现人物。" : "场景概念图，画面中不要出现人物。");
        return text.toString();
    }

    private static String propPrompt(String name, DramaStyles.Spec spec) {
        String styleLine = spec.prefix();
        StringBuilder text = new StringBuilder();
        if (spec.realistic()) {
            text.append(name).append("实拍静物。");
            text.append("真实相机拍摄，完整展示外形、材质、磨损和标志性细节，可被手持或放置。");
            text.append("正面与微距特写，干净背景。");
            text.append(styleLine).append("。");
            text.append("实拍静物，画面中不要出现人物。");
            return text.toString();
        }
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
     * 是否仍是抽取骨架的无效外形句（无性别/衣着等可识别信息）。
     * 页面「外形设定」与二次增强覆盖都依赖此判断。
     */
    public static boolean isSkeletonIdentity(String name, String identity) {
        if (!StringUtils.hasText(identity)) {
            return true;
        }
        String text = identity.trim();
        if (text.contains("外貌、发型、服装和年龄必须保持一致")) {
            return true;
        }
        if (text.contains("与主身份同一张脸和发型")) {
            return true;
        }
        if (StringUtils.hasText(name) && text.equals(name.trim() + "的外貌、发型、服装和年龄必须保持一致")) {
            return true;
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

    static boolean isObsoleteLiveRealismCharacterSheet(String prompt, String type, String styleKey) {
        if (!DramaStyles.isRealistic(styleKey) || !StringUtils.hasText(prompt)) {
            return false;
        }
        if (StringUtils.hasText(type) && !DramaContract.ASSET_TYPE_CHARACTER.equals(type)) {
            return false;
        }
        if (DramaStyles.REALISTIC_3D.equals(styleKey)) {
            // 纯白底定妆保留。靠窗房间或灰底棚拍的旧句整段重写。
            if (prompt.contains("纯白无缝背景") && !prompt.contains("靠窗的朴素墙面")) {
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
        // 已是含真人写真定妆句的新版固定四格则保留。
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
