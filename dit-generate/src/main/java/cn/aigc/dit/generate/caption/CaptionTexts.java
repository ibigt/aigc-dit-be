package cn.aigc.dit.generate.caption;

/**
 * 底部字幕换行与展示规范化。按码点计，避免把一个汉字拆开。
 */
public final class CaptionTexts {

    public static final int DEFAULT_WRAP_CHARS = 16;

    private CaptionTexts() {
    }

    /**
     * 去掉字幕展示时句末标点（如「，」「。」），保留正文；不影响存储的原始 cue 文本。
     */
    public static String stripTrailingPunctuation(String text) {
        if (text == null) {
            return "";
        }
        String value = text.trim();
        if (value.isEmpty()) {
            return value;
        }
        int end = value.length();
        while (end > 0) {
            int cp = value.codePointBefore(end);
            int count = Character.charCount(cp);
            if (!isTrailingDisplayPunctuation(cp)) {
                break;
            }
            end -= count;
        }
        return end == value.length() ? value : value.substring(0, end).trim();
    }

    private static boolean isTrailingDisplayPunctuation(int codePoint) {
        return switch (codePoint) {
            case '，', ',', '。', '.', '！', '!', '？', '?', '；', ';', '：', ':',
                 '、', '…', '—', '-', '～', '~', '·', '•',
                 '"', '\'', '“', '”', '‘', '’', '」', '』', '）', ')', '】', ']' -> true;
            default -> false;
        };
    }

    public static String wrap(String text) {
        return wrap(text, DEFAULT_WRAP_CHARS);
    }

    public static String wrap(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String trimmed = stripTrailingPunctuation(text);
        if (trimmed.isEmpty() || maxChars < 1) {
            return trimmed;
        }
        int[] codePoints = trimmed.codePoints().toArray();
        if (codePoints.length <= maxChars) {
            return trimmed;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < codePoints.length; i++) {
            if (i > 0 && i % maxChars == 0) {
                out.append('\n');
            }
            out.appendCodePoint(codePoints[i]);
        }
        return out.toString();
    }
}
