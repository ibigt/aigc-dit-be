package cn.aigc.dit.server.drama;

import cn.aigc.dit.server.drama.entity.DramaShot;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 从拆镜 Action/标题推断景别，决定首尾帧是链式落幅还是另画开场静帧。
 */
public final class DramaShotScales {

    public enum Bucket {
        CLOSE,
        MEDIUM,
        WIDE
    }

    private DramaShotScales() {
    }

    public static Bucket of(DramaShot shot) {
        if (shot == null) {
            return Bucket.MEDIUM;
        }
        Bucket action = classify(shot.getNarrativeGoal(), true);
        if (action != null) {
            return action;
        }
        Bucket title = classify(shot.getTitle(), false);
        if (title != null) {
            return title;
        }
        Bucket scene = classify(shot.getScene(), false);
        if (scene != null) {
            return scene;
        }
        return Bucket.MEDIUM;
    }

    public static Bucket ofText(String... parts) {
        if (parts == null || parts.length == 0) {
            return Bucket.MEDIUM;
        }
        Bucket first = classify(parts[0], true);
        if (first != null) {
            return first;
        }
        for (int i = 1; i < parts.length; i++) {
            Bucket next = classify(parts[i], false);
            if (next != null) {
                return next;
            }
        }
        return Bucket.MEDIUM;
    }

    /**
     * Action 可用「含特写」兜底；标题/场景只认句首景别，避免「怀表特写」把中景误判成特写。
     */
    static Bucket classify(String text, boolean allowContains) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String raw = text.trim();
        if (starts(raw, "中近景", "中景", "过肩", "跟镜头")) {
            return Bucket.MEDIUM;
        }
        if (starts(raw, "特写", "近景", "微距") || (allowContains && raw.contains("特写"))) {
            return Bucket.CLOSE;
        }
        if (starts(raw, "全景", "远景", "大远景", "大全景")
                || (allowContains && (raw.contains("全景") || raw.contains("远景")))) {
            return Bucket.WIDE;
        }
        return null;
    }

    /**
     * 同场、景别兼容、且无新角色入画才自动链式。
     * 特写互切、换场、新角色进门等视为切镜，默认另画开场静帧；仍可用「使用上一镜落幅」手动覆盖。
     */
    public static boolean sameChapter(DramaShot previous, DramaShot next) {
        return previous != null && next != null
                && previous.getChapterId() != null
                && previous.getChapterId().equals(next.getChapterId());
    }

    public static boolean scaleCompatible(DramaShot previous, DramaShot next) {
        if (previous == null || next == null) {
            return false;
        }
        Bucket from = of(previous);
        Bucket to = of(next);
        if (from == Bucket.CLOSE || to == Bucket.CLOSE) {
            return from == to;
        }
        return true;
    }

    /**
     * 下一镜点名了上一镜没有的角色（如局长闯入）时不自动接落幅，否则首帧里没有新角色，模型会拿已有的人顶替。
     */
    public static boolean castCompatible(DramaShot previous, DramaShot next) {
        if (previous == null || next == null) {
            return false;
        }
        List<String> incoming = names(next);
        if (incoming.isEmpty()) {
            return true;
        }
        List<String> already = names(previous);
        if (already.isEmpty()) {
            return false;
        }
        for (String name : incoming) {
            if (!already.contains(name)) {
                return false;
            }
        }
        return true;
    }

    public static boolean chainable(DramaShot previous, DramaShot next) {
        return sameChapter(previous, next) && scaleCompatible(previous, next) && castCompatible(previous, next);
    }

    private static List<String> names(DramaShot shot) {
        List<String> values = new ArrayList<>();
        if (shot == null || shot.getCharacterNames() == null) {
            return values;
        }
        for (String name : shot.getCharacterNames()) {
            if (StringUtils.hasText(name)) {
                values.add(name.trim());
            }
        }
        return values;
    }

    private static boolean starts(String text, String... prefixes) {
        for (String prefix : prefixes) {
            if (text.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
