package cn.aigc.dit.server.drama;

import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Set;

/**
 * 对白说话人归一与情绪闭集。旁白类 speaker 统一为 {@link #NARRATION}，不进角色资产。
 */
public final class DramaDialogueSpeakers {

    public static final String NARRATION = "旁白";

    /** 闭集情绪；空/非法一律丢弃。 */
    public static final Set<String> EMOTIONS = Set.of(
            "高兴", "愤怒", "悲伤", "恐惧", "惊讶", "厌恶", "紧张");

    private static final Set<String> NARRATION_ALIASES = Set.of(
            "旁白", "画外音", "解说", "旁白音", "旁白声", "画外",
            "vo", "os", "v.o.", "o.s.", "v.o", "o.s");

    private DramaDialogueSpeakers() {
    }

    public static boolean isNarration(String speaker) {
        if (!StringUtils.hasText(speaker)) {
            return false;
        }
        return NARRATION_ALIASES.contains(speaker.trim().toLowerCase(Locale.ROOT));
    }

    /** 旁白别名归一为「旁白」；其它原样 trim。 */
    public static String normalizeSpeaker(String speaker) {
        if (!StringUtils.hasText(speaker)) {
            return speaker == null ? "" : speaker.trim();
        }
        String trimmed = speaker.trim();
        return isNarration(trimmed) ? NARRATION : trimmed;
    }

    /** 闭集校验；空或非法返回 null。 */
    public static String sanitizeEmotion(String emotion) {
        if (!StringUtils.hasText(emotion)) {
            return null;
        }
        String value = emotion.trim();
        return EMOTIONS.contains(value) ? value : null;
    }

    public static boolean isRealCharacterName(String name) {
        return StringUtils.hasText(name) && !isNarration(name);
    }
}
