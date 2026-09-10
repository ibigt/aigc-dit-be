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

    private static final Map<String, Spec> BY_KEY = new LinkedHashMap<>();

    static {
        register(new Spec(
                ANIME_COMIC,
                "二次元漫画风",
                "二次元漫画风，干净线稿，赛璐璐平涂，清晰分色，电影分镜构图，不要写实摄影质感",
                "/drama/styles/anime-comic.jpg"));
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
