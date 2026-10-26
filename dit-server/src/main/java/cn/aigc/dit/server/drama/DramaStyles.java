package cn.aigc.dit.server.drama;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 漫剧风格目录。用户只选 styleKey（图例卡片）；stylePrefix 仅供分镜编译注入。
 */
public final class DramaStyles {

    public static final String ANIME_COMIC = "anime-comic";
    public static final String LIVE_REALISM = "live-realism";
    public static final String ANIME_WUXIA = "anime-wuxia";
    public static final String COSTUME_COMIC = "costume-comic";
    public static final String REPUBLICAN_ERA = "republican-era";
    public static final String CYBERPUNK = "cyberpunk";
    public static final String SCI_FI = "sci-fi";
    public static final String RURAL = "rural";
    public static final String GUOFENG_3D = "guofeng-3d";
    public static final String NEON_CYBER = "neon-cyber";
    public static final String KDRAMA_SOFT = "kdrama-soft";
    public static final String JP_YOUTH = "jp-youth";
    public static final String SCI_FI_CINEMA = "sci-fi-cinema";
    public static final String AMERICAN_RETRO = "american-retro";

    /**
     * 旧别名：曾独立存在的「真人3D风」，已合并到 {@link #LIVE_REALISM}「现代写实风」。
     * 仅用于历史数据回退与迁移识别，注册表不再收录。
     */
    public static final String LEGACY_REALISTIC_3D = "realistic-3d";

    /** 风格大类：写实（4 风格共用实拍级模板规则）与动漫（共用现骨架）。 */
    public static final String CATEGORY_REALISTIC = "realistic";
    public static final String CATEGORY_ANIME = "anime";

    /** 只注入分镜视频编译，不写入主体设定图。 */
    public static final String VIDEO_SCENE_CLAUSE = "连续运动的电影场面，禁止设定表和白底静物";

    private static final Map<String, Spec> BY_KEY = new LinkedHashMap<>();

    static {
        register(new Spec(
                ANIME_COMIC,
                "二次元漫画风",
                "二次元漫画风，干净线稿，赛璐璐平涂，清晰分色，电影分镜构图，不要写实摄影质感",
                "/drama/styles/anime-comic.jpg",
                CATEGORY_ANIME));
        register(new Spec(
                LIVE_REALISM,
                "现代写实风",
                "现代写实风，极致写实实拍摄影，真人实拍写真定妆，自然肤质与可见毛孔，真实发丝与布料质感，棚拍柔光，不要3D渲染，不要CG数字人，不要塑料皮肤，不要二次元卡通，不要古装武侠仙侠，不要平涂线稿",
                "/drama/styles/live-realism.jpg",
                CATEGORY_REALISTIC));
        register(new Spec(
                ANIME_WUXIA,
                "动漫武侠风",
                "动漫武侠风，精致动漫作画，飘逸古装与刀剑气韵，山水雾气竹林光影，赛璐璐上色，清晰线稿，动态打斗场面，电影分镜构图，不要真实人脸，不要现代都市，不要 3D 写实",
                "/drama/styles/anime-wuxia.jpg",
                CATEGORY_ANIME));
        register(new Spec(
                COSTUME_COMIC,
                "古装漫画风",
                "古装漫画风，精致漫画作画，古风汉服与宫廷园林，亭台楼阁烟雨江南，工笔配色，清晰墨线，典雅人物造型，电影分镜构图，不要真实人脸，不要现代服饰，不要 3D 写实",
                "/drama/styles/costume-comic.jpg",
                CATEGORY_ANIME));
        register(new Spec(
                REPUBLICAN_ERA,
                "民国漫画风",
                "民国漫画风，精致漫画作画，旗袍长衫与洋装礼帽，弄堂电车洋房霓虹灯，复古暖色调，清晰线稿，上海滩时代风貌，电影分镜构图，不要真实人脸，不要古装，不要 3D 写实",
                "/drama/styles/republican-era.jpg",
                CATEGORY_ANIME));
        register(new Spec(
                CYBERPUNK,
                "赛博朋克风",
                "赛博朋克动漫风，霓虹都市与全息投影，义体改造与雨夜街景，高对比紫蓝橙红霓虹色，清晰线稿，未来科技感，电影分镜构图，不要真实人脸，不要古装，不要 3D 写实",
                "/drama/styles/cyberpunk.jpg",
                CATEGORY_ANIME));
        register(new Spec(
                SCI_FI,
                "未来科幻风",
                "未来科幻动漫风，宇宙飞船与外星基地，机甲战士与星际冒险，冷色调金属质感，清晰线稿，高科技场景，电影分镜构图，不要真实人脸，不要古装，不要 3D 写实",
                "/drama/styles/sci-fi.jpg",
                CATEGORY_ANIME));
        register(new Spec(
                RURAL,
                "乡土漫画风",
                "乡土漫画风，朴实漫画作画，田野村庄与黄土老屋，烟火日常与朴素人物，温暖大地色系，清晰线稿，田园生活气息，电影分镜构图，不要真实人脸，不要古装，不要 3D 写实",
                "/drama/styles/rural.jpg",
                CATEGORY_ANIME));
        register(new Spec(
                GUOFENG_3D,
                "古风写实风",
                "古风写实风，国风真人实拍写真摄影，棚拍柔光与自然肤质，清晰五官与独立发丝，汉服广袖与发冠金饰，真实布料与金属质感，浅景深，商业人像与杂志写真质感，不要二次元卡通，不要3D国漫厚涂，不要CG数字人，不要塑料皮肤，不要平涂线稿，不要现代都市",
                "/drama/styles/guofeng-3d.jpg",
                CATEGORY_REALISTIC));
        register(new Spec(
                NEON_CYBER,
                "霓虹赛博风",
                "霓虹赛博写实风，赛博都市真人实拍写真摄影，高对比紫蓝橙红霓虹色，霓虹光与自然肤质，清晰五官与独立发丝，浅景深，35mm 电影镜头，商业人像与杂志写真质感，不要二次元卡通，不要3D国漫厚涂，不要CG数字人，不要塑料皮肤，不要平涂线稿，不要古装汉服",
                "/drama/styles/neon-cyber.jpg",
                CATEGORY_REALISTIC));
        register(new Spec(
                KDRAMA_SOFT,
                "韩剧柔光风",
                "韩剧柔光写实风，韩剧真人实拍摄影，柔和暖调打光与奶油色滤镜，水润透亮肤质与自然裸妆感，精致五官与柔顺发丝，浅景深虚化，85mm 人像镜头，浪漫唯美与杂志画报质感，不要二次元卡通，不要3D渲染，不要CG数字人，不要塑料皮肤，不要平涂线稿，不要古装汉服",
                "/drama/styles/kdrama-soft.jpg",
                CATEGORY_REALISTIC));
        register(new Spec(
                JP_YOUTH,
                "日系青春风",
                "日系青春写实风，日剧与青春电影真人实拍摄影，柔和高调自然光与轻微过曝胶片色调，清透素颜质感与蓬松空气感发型，干净清爽的校服或休闲穿搭，逆光通透与少女感构图，35mm 胶片颗粒质感，不要二次元卡通，不要3D渲染，不要CG数字人，不要塑料皮肤，不要平涂线稿，不要古装汉服，不要浓妆艳抹",
                "/drama/styles/jp-youth.jpg",
                CATEGORY_REALISTIC));
        register(new Spec(
                SCI_FI_CINEMA,
                "科技电影风",
                "科技电影写实风，科幻电影真人实拍大片摄影，冷调青橙对比与体积光雾，玻璃金属与全息投影反光，自然肤质与清晰五官发丝，浅景深，变形宽银幕镜头与水平蓝光光晕，电影级调色与商业大片质感，不要二次元卡通，不要3D渲染，不要CG数字人，不要塑料皮肤，不要平涂线稿，不要古装汉服",
                "/drama/styles/sci-fi-cinema.jpg",
                CATEGORY_REALISTIC));
        register(new Spec(
                AMERICAN_RETRO,
                "美式复古风",
                "美式复古写实风，美式复古真人实拍写真摄影，柯达胶片暖调与褪色颗粒，复古牛仔夹克或波点连衣裙与老式卷发，公路餐馆霓虹招牌与老爷车街景，正午硬光或暖黄昏，自然肤质与清晰五官发丝，浅景深，35mm 胶片镜头，复古画报与电影剧照质感，不要二次元卡通，不要3D渲染，不要CG数字人，不要塑料皮肤，不要平涂线稿，不要古装汉服，不要现代科技感",
                "/drama/styles/american-retro.jpg",
                CATEGORY_REALISTIC));
    }

    /**
     * 分镜视频前缀：在视觉风格句上加长半句电影场面约束。设定图仍用 {@link Spec#prefix()}。
     */
    public static String withVideoSceneClause(String stylePrefix) {
        String text = StringUtils.hasText(stylePrefix)
                ? stylePrefix.trim()
                : require(ANIME_COMIC).prefix();
        text = stripFaceSafetyNegations(text);
        if (text.contains(VIDEO_SCENE_CLAUSE)) {
            return text;
        }
        return text + "，" + VIDEO_SCENE_CLAUSE;
    }

    /**
     * 视频审图是图文一起看的。提示词里写「真实人脸 / 真人照片」反而更容易把二次元设定图判成真人。
     */
    static String stripFaceSafetyNegations(String text) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        return text.replace("，不要真实人脸，不要真人照片", "")
                .replace("不要真实人脸，不要真人照片，", "")
                .replace("，不要真实人脸", "")
                .replace("不要真实人脸，", "");
    }

    private DramaStyles() {
    }

    public static List<Spec> all() {
        return List.copyOf(BY_KEY.values());
    }

    public static Spec require(String styleKey) {
        String key = StringUtils.hasText(styleKey) ? styleKey.trim() : ANIME_COMIC;
        Spec spec = BY_KEY.get(key);
        if (spec == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "暂不支持该视觉风格");
        }
        return spec;
    }

    /**
     * 列表/详情展示用：未知或已下线的 style_key 回退到二次元漫画风，避免整表加载失败。
     * 已知历史 key（旧「真人3D风」）自动别名到现代写实风。
     * 创建/改风格仍走 {@link #require(String)}。
     */
    public static Spec resolveOrDefault(String styleKey) {
        if (!StringUtils.hasText(styleKey)) {
            return require(ANIME_COMIC);
        }
        String key = styleKey.trim();
        if (LEGACY_REALISTIC_3D.equals(key)) {
            return require(LIVE_REALISM);
        }
        Spec spec = BY_KEY.get(key);
        return spec != null ? spec : require(ANIME_COMIC);
    }

    /** 大类判断：写实风格共用实拍级模板规则（未知 key 按回退风格即动漫处理）。 */
    public static boolean isRealistic(String styleKey) {
        return resolveOrDefault(styleKey).realistic();
    }

    private static void register(Spec spec) {
        BY_KEY.put(spec.key(), spec);
    }

    public record Spec(String key, String name, String prefix, String cover, String category) {

        public boolean realistic() {
            return CATEGORY_REALISTIC.equals(category);
        }
    }
}
