package cn.aigc.dit.generate.caption;

/**
 * 底部字幕换行。按码点计，避免把一个汉字拆开。
 */
public final class CaptionTexts {

    public static final int DEFAULT_WRAP_CHARS = 16;

    private CaptionTexts() {
    }

    public static String wrap(String text) {
        return wrap(text, DEFAULT_WRAP_CHARS);
    }

    public static String wrap(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
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
