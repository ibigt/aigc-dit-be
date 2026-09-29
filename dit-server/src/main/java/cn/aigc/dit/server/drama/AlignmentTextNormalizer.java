package cn.aigc.dit.server.drama;

import java.text.Normalizer;

/** Removes speaker labels and formatting while retaining the display text separately. */
public final class AlignmentTextNormalizer {

    private static final String[] DIGITS = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
    private static final String[] NUMBER_UNITS = {"", "十", "百", "千"};
    private static final String[] LETTER_PRONUNCIATIONS = {
            "诶", "比", "西", "迪", "伊", "艾弗", "吉", "艾尺", "艾", "杰", "开", "艾勒", "艾姆",
            "恩", "欧", "批", "丘", "阿尔", "艾丝", "提", "优", "维", "达不溜", "艾克斯", "歪", "贼德"
    };

    private AlignmentTextNormalizer() {
    }

    public static NormalizedText normalizeDialogue(String dialogue) {
        String raw = String.join("", CaptionAligner.lines(dialogue));
        return new NormalizedText(raw, normalizeText(raw));
    }

    public static String normalizeText(String text) {
        if (text == null || text.isBlank()) return "";
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder result = new StringBuilder();
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            if (codePoint >= '0' && codePoint <= '9') {
                int end = offset + Character.charCount(codePoint);
                while (end < normalized.length()) {
                    int next = normalized.codePointAt(end);
                    if (next < '0' || next > '9') break;
                    end += Character.charCount(next);
                }
                result.append(normalizeNumber(normalized.substring(offset, end)));
                offset = end;
                continue;
            }
            if (isAsciiLetter(codePoint)) {
                result.append(LETTER_PRONUNCIATIONS[Character.toLowerCase(codePoint) - 'a']);
            } else if (!Character.isWhitespace(codePoint) && !isPunctuation(codePoint)) {
                result.appendCodePoint(codePoint);
            }
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private static String normalizeNumber(String value) {
        if (value.length() == 1) return DIGITS[value.charAt(0) - '0'];
        if (value.charAt(0) == '0' || value.length() > 4) return digitByDigit(value);
        int number;
        try {
            number = Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return digitByDigit(value);
        }
        if (number == 0) return DIGITS[0];
        StringBuilder result = new StringBuilder();
        boolean pendingZero = false;
        for (int unit = 0; unit < NUMBER_UNITS.length && number > 0; unit++) {
            int digit = number % 10;
            number /= 10;
            if (digit == 0) {
                pendingZero = result.length() > 0;
                continue;
            }
            if (pendingZero) {
                result.insert(0, DIGITS[0]);
                pendingZero = false;
            }
            result.insert(0, DIGITS[digit] + NUMBER_UNITS[unit]);
        }
        if (result.indexOf("一十") == 0) result.deleteCharAt(0);
        return result.toString();
    }

    private static String digitByDigit(String value) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            result.append(DIGITS[value.charAt(index) - '0']);
        }
        return result.toString();
    }

    private static boolean isAsciiLetter(int codePoint) {
        return codePoint >= 'A' && codePoint <= 'Z' || codePoint >= 'a' && codePoint <= 'z';
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
