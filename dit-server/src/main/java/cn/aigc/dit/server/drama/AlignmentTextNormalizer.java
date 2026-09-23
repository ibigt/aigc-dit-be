package cn.aigc.dit.server.drama;

import java.text.Normalizer;
import java.util.Locale;

/** Removes speaker labels and formatting while retaining the display text separately. */
public final class AlignmentTextNormalizer {

    private static final String[] DIGITS = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};

    private AlignmentTextNormalizer() {
    }

    public static NormalizedText normalizeDialogue(String dialogue) {
        String raw = String.join("", CaptionAligner.lines(dialogue));
        return new NormalizedText(raw, normalizeText(raw));
    }

    public static String normalizeText(String text) {
        if (text == null || text.isBlank()) return "";
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder();
        normalized.codePoints().forEach(codePoint -> {
            if (Character.isWhitespace(codePoint) || isPunctuation(codePoint)) return;
            if (codePoint >= '0' && codePoint <= '9') {
                result.append(DIGITS[codePoint - '0']);
            } else {
                result.appendCodePoint(codePoint);
            }
        });
        return result.toString();
    }

    private static boolean isPunctuation(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION
                || type == Character.MATH_SYMBOL
                || type == Character.CURRENCY_SYMBOL
                || type == Character.MODIFIER_SYMBOL
                || type == Character.OTHER_SYMBOL;
    }

    public record NormalizedText(String rawText, String normalizedText) {
    }
}
