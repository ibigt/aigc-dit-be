package cn.aigc.dit.generate.describe;

import java.util.Locale;

/**
 * JoyCaption 式图生文提示：可判别特征优先；角色带 CGI 短尾，场景/道具不走人设 CGI。
 */
public final class ImageDescribePrompts {

    private static final String CGI_TAIL =
            "人物写成原创 CGI 数字人（电影级仿真人即可），禁止写「真人照片」「实拍证件照」。"
                    + "不要第二个人、不要字幕水印、不要空话评价（禁止堆砌 8K/次表面散射等无判别信息的词）。";

    private static final String COMMON_TAIL =
            "用中文输出两段，且只输出这两段：\n"
                    + "【identity】一行短句（≤40字）：年龄气质 + 最可识别的外形锚点（发型/五官/标志配饰）。\n"
                    + "【t2i_prompt】一段可直接粘贴给文生图的提示词，按此顺序写："
                    + "五官与脸型细节 → 发型鬓角灰白 → 服装材质与左右配饰位置 → 姿态构图 → 光影背景；"
                    + CGI_TAIL;

    public static final String CHARACTER_CLOSEUP =
            "这是一张竖版角色头肩特写参考图。请抓住「换一张脸就会错」的特征，便于文生图还原同一张脸。\n"
                    + "优先写清：脸型、眉眼鼻口、肤质皱纹胡须、发型分线鬓角灰白、表情视线、"
                    + "可见衣领外套颜色、面部约占画面比例、主光方向。次要才写氛围形容词。\n"
                    + COMMON_TAIL;

    public static final String CHARACTER_FULL =
            "这是一张竖版角色全身设定参考图。请抓住「换一个人就会错」的特征，便于文生图还原同一人设。\n"
                    + "优先写清：年龄性别族群观感、脸型五官、发型鬓角灰白、外套/内搭/裤/鞋颜色材质、"
                    + "腰带徽章等配饰及左右位置、站姿手脚、灰底棚拍光影。次要才写氛围形容词。\n"
                    + COMMON_TAIL;

    public static final String SCENE =
            "这是一张无人室内空镜参考图。请穷尽描述空间以便文生图还原同一走廊/门口。\n"
                    + "关注：机位高低与左右偏、前景遮挡、墙面上下分区颜色、地面铺装、天花灯具、"
                    + "门框玻璃拉手开合方向、门外房间陈设轮廓、冷暖光对比、氛围；画面不要人物。\n"
                    + "用中文输出两段，且只输出这两段：\n"
                    + "【identity】一行短句：场景类型 + 关键空间特征。\n"
                    + "【t2i_prompt】一段可直接粘贴给文生图的详细空镜提示词；禁止人物与可读招牌文字。";

    public static final String PROP =
            "这是一张道具/物件参考图。请抓住「换一件就会错」的外形特征，便于文生图还原同一物件。\n"
                    + "优先写清：品类用途、主体轮廓比例、材质质感、主色与局部配色、标志性细节与文字纹理、"
                    + "摆放角度与光影；不要写人物。\n"
                    + "用中文输出两段，且只输出这两段：\n"
                    + "【identity】一行短句：物件品类 + 最可识别锚点。\n"
                    + "【t2i_prompt】一段可直接粘贴给文生图的物件提示词；禁止人物与无关场景叙事。";

    private ImageDescribePrompts() {
    }

    /** 按脸占比粗分：≥0.18 用特写，否则全身。调用方无占比时默认全身。 */
    public static String forFaceRatio(Double faceRatio) {
        if (faceRatio != null && faceRatio >= 0.18) {
            return CHARACTER_CLOSEUP;
        }
        return CHARACTER_FULL;
    }

    /**
     * 按资产类型选模板。角色默认全身；场景/道具用专用模板；未知类型回退角色全身。
     *
     * @param assetType 业务类型文案，如「角色」「场景」「道具」
     */
    public static String forAssetType(String assetType) {
        String type = assetType == null ? "" : assetType.trim();
        String lower = type.toLowerCase(Locale.ROOT);
        if ("场景".equals(type) || "scene".equals(lower) || "location".equals(lower)) {
            return SCENE;
        }
        if ("道具".equals(type) || "prop".equals(lower) || "object".equals(lower)) {
            return PROP;
        }
        return CHARACTER_FULL;
    }
}
