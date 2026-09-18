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

    /** 只注入分镜视频编译，不写入主体设定图。 */
    public static final String VIDEO_SCENE_CLAUSE = "连续运动的电影场面，禁止设定表和白底静物";

    private static final Map<String, Spec> BY_KEY = new LinkedHashMap<>();

    static {
        register(new Spec(
                ANIME_COMIC,
                "二次元漫画风",
                "二次元漫画风，干净线稿，赛璐璐平涂，清晰分色，电影分镜构图，不要写实摄影质感",
                "/drama/styles/anime-comic.jpg"));
        register(new Spec(
                LIVE_REALISM,
                "真人写实风",
                "高精度二次元写实，半写实3D渲染，精致CG质感，3D国漫轻写实厚涂，棚拍柔光，写实皮肤毛发与布料质感，不要卡通平涂线稿",
                "/drama/styles/live-realism.jpg"));
        register(new Spec(
                ANIME_WUXIA,
                "动漫武侠风",
                "动漫武侠风，精致动漫作画，飘逸古装与刀剑气韵，山水雾气竹林光影，赛璐璐上色，清晰线稿，动态打斗场面，电影分镜构图，不要真实人脸，不要现代都市，不要 3D 写实",
                "/drama/styles/anime-wuxia.jpg"));
        register(new Spec(
                COSTUME_COMIC,
                "古装漫画风",
                "古装漫画风，精致漫画作画，古风汉服与宫廷园林，亭台楼阁烟雨江南，工笔配色，清晰墨线，典雅人物造型，电影分镜构图，不要真实人脸，不要现代服饰，不要 3D 写实",
                "/drama/styles/costume-comic.jpg"));
        register(new Spec(
                REPUBLICAN_ERA,
                "民国漫画风",
                "民国漫画风，精致漫画作画，旗袍长衫与洋装礼帽，弄堂电车洋房霓虹灯，复古暖色调，清晰线稿，上海滩时代风貌，电影分镜构图，不要真实人脸，不要古装，不要 3D 写实",
                "/drama/styles/republican-era.jpg"));
        register(new Spec(
                CYBERPUNK,
                "赛博朋克风",
                "赛博朋克动漫风，霓虹都市与全息投影，义体改造与雨夜街景，高对比紫蓝橙红霓虹色，清晰线稿，未来科技感，电影分镜构图，不要真实人脸，不要古装，不要 3D 写实",
                "/drama/styles/cyberpunk.jpg"));
        register(new Spec(
                SCI_FI,
                "未来科幻风",
                "未来科幻动漫风，宇宙飞船与外星基地，机甲战士与星际冒险，冷色调金属质感，清晰线稿，高科技场景，电影分镜构图，不要真实人脸，不要古装，不要 3D 写实",
                "/drama/styles/sci-fi.jpg"));
        register(new Spec(
                RURAL,
                "乡土漫画风",
                "乡土漫画风，朴实漫画作画，田野村庄与黄土老屋，烟火日常与朴素人物，温暖大地色系，清晰线稿，田园生活气息，电影分镜构图，不要真实人脸，不要古装，不要 3D 写实",
                "/drama/styles/rural.jpg"));
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

    private static void register(Spec spec) {
        BY_KEY.put(spec.key(), spec);
    }

    public record Spec(String key, String name, String prefix, String cover) {
    }
}
