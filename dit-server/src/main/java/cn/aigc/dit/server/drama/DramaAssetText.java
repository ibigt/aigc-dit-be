package cn.aigc.dit.server.drama;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 资产提示词里的「自我更正残留」清理。
 *
 * <p><b>问题。</b>模型写角色外形时，偶尔先落一句英文性别再自己改口，中文的那句才是它认可���版本：
 * <pre>
 *   30岁 male? no; 30岁，男，瘦削长脸，细眉塌眼，黑发散乱，身形干瘦佝偻；穿褪色灰褐太监袍、旧布帽与露趾布鞋。
 *   55岁，female? no; 55岁，宽额方脸，法令纹深，灰黑发梳低髻，身材粗壮结实；穿深棕粗布宫装…
 * </pre>
 * 这些是模型原样吐出来的（后端没有任何代码会生成它们），14 环境 2026-11-05 实测一集 40 条资产里
 * 有 4 条中招（苏培盛 / 老嬷嬷 / 冷宫太监 / 皇帝）。页面「外形设定」直接显示这段垃圾，
 * 拼进生图提示词也一起脏。
 *
 * <p><b>为什么在这里机械处理，而不是只改提示词。</b>「性别写英文」是模型的行为偏差，
 * 提示词只能降低概率，不能保证不再犯；而这段残留是<b>有固定形状</b>的
 * （同一段文字 → 英文性别 → 问号 → 否定 → 同一段文字），代码能认出来。认出来之后
 * 丢掉第一版草稿、留下模型认可的第二版，既去掉了英文，也顺手修掉了「30岁 30岁」这种重复。
 *
 * <p>刻意保守：只动「英文性别词 + 自我更正」这一类，不碰其他英文（iPhone、SUV 这类可能是道具正名），
 * 也不动中文内容。清理后的文本若仍含英文，由 {@link #hasEnglish} 报出来给校验器提醒。
 */
public final class DramaAssetText {

    private DramaAssetText() {
    }

    /** 英文性别/称谓词 → 中文。模型偶尔不按「性别用『男』『女』」的约定写。 */
    private static final Map<String, String> ROLE_WORDS = new LinkedHashMap<>();

    static {
        ROLE_WORDS.put("male", "男");
        ROLE_WORDS.put("female", "女");
        ROLE_WORDS.put("man", "男人");
        ROLE_WORDS.put("woman", "女人");
        ROLE_WORDS.put("boy", "男孩");
        ROLE_WORDS.put("girl", "女孩");
        ROLE_WORDS.put("gentleman", "绅士");
        ROLE_WORDS.put("lady", "女士");
        ROLE_WORDS.put("mister", "先生");
        ROLE_WORDS.put("miss", "小姐");
    }

    /** 自我更正片段：英文词 + 问号（+ 可选否定词）+ 可选收尾分号。「male? no;」「female?」都算。 */
    private static final Pattern SELF_CORRECTION = Pattern.compile(
            "[A-Za-z]{2,}\\s*[?？]\\s*(?:no|not|nope|不是|不对)?\\s*[;；]?(?![A-Za-z])",
            Pattern.CASE_INSENSITIVE);

    /** 带括号的自我更正片段：[male? no]、【female?】。 */
    private static final Pattern BRACKETED_SELF_CORRECTION = Pattern.compile(
            "[\\[【(（]\\s*[A-Za-z]{2,}\\s*[?？]\\s*(?:no|not|nope|不是|不对)?\\s*[\\]】)）]",
            Pattern.CASE_INSENSITIVE);

    /**
     * 「同一段文字 + 英文性别 + 同一段文字」：模型先写了一遍，改了性别又重写一遍。
     *
     * <p>捕获组 1 是第一版草稿，末尾的 {@code \\1} 要求第二遍必须重���同一段文字 ——
     * 「30岁 male? no; 30岁，男」命中；「30岁 male? no; 黑色长裤」不命中（那不是重写，是另一句话）。
     */
    private static final Pattern DRAFT_REWRITE = Pattern.compile(
            "([^\\s，,；;、：:]{1,20})([\\s，,、；;：:]*)"
                    + "(?:male|female|man|woman|boy|girl|gentleman|lady|mister|miss)"
                    + "\\s*[?？]?\\s*(?:no|not|nope|不是|不对)?[\\s，,、；;：:]*\\1",
            Pattern.CASE_INSENSITIVE);

    /** 开头两段：「第一版草稿」+ 分隔 + 「第二版」。用来把改口后残留的第一版删掉。 */
    private static final Pattern LEADING_SEGMENTS = Pattern.compile(
            "^([^\\s，,；;、：:]{1,20})([\\s，,、；;：:]+)([^\\s，,；;、：:]{1,20})");

    /** 任意位置的英文单词（字母、字母数字组合）。 */
    private static final Pattern ENGLISH_WORD = Pattern.compile("[A-Za-z]{2,}");

    /**
     * 清理模型写出的资产文本（外形设定 / 描述 / 生图提示词 / 声音锁定）。
     *
     * @param raw 原文，可为 null
     * @return 清理后的文本；入参为 null 时返回 null
     */
    public static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.strip();
        if (text.isEmpty()) {
            return text;
        }
        boolean hadArtifact = hasArtifact(text);
        text = BRACKETED_SELF_CORRECTION.matcher(text).replaceAll("");
        text = DRAFT_REWRITE.matcher(text).replaceAll("$1");
        text = SELF_CORRECTION.matcher(text).replaceAll("");
        if (hadArtifact) {
            text = dropLeadingDraft(text);
        }
        text = mapRoleWords(text);
        return tidy(text);
    }

    /**
     * 文本里还有没有「英文性别词 / 自我更正片段」。
     *
     * <p>给校验器用：残留说明模型没按提示词写，值得提醒一句（但不判死）。
     */
    public static boolean hasEnglish(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return hasArtifact(text) || containsRoleWord(text);
    }

    /** 文本里还有没有自我更正片段（英文词 + 问号）。 */
    public static boolean hasArtifact(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return SELF_CORRECTION.matcher(text).find()
                || BRACKETED_SELF_CORRECTION.matcher(text).find();
    }

    /**
     * 文本里还有没有成串的拉丁字母。
     *
     * <p>清理规则只认得「英文性别词」和「自我更正片段」，别的英文（SUV、iPhone…）会原样留着。
     * 资产提示词要求只写中文，所以这里给校验器一个提醒的依据 —— 只提醒，不判死。
     */
    public static boolean hasLatinWord(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return ENGLISH_WORD.matcher(text).find();
    }

    private static boolean containsRoleWord(String text) {
        Matcher matcher = Pattern.compile(
                        "(?<![A-Za-z])(?:" + String.join("|", ROLE_WORDS.keySet()) + ")(?![A-Za-z])",
                        Pattern.CASE_INSENSITIVE)
                .matcher(text);
        return matcher.find();
    }

    /**
     * 改口之后残留的「第一版草稿」：模型先写了一遍（带英文性别），改口后又写了一遍，第二遍才是它认可的版本。
     *
     * <p>只有两段开头有共同前缀时才删第一版，且共同前缀得够长（≥3 字）或第一版以性别词结尾 ——
     * 「30岁左右的男性 30岁，男」删前一段，「黑色长裤 黑色短袖」保留两段（那是两件衣服，不是一句话改了两遍）。
     */
    private static String dropLeadingDraft(String text) {
        Matcher matcher = LEADING_SEGMENTS.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        String first = matcher.group(1);
        String second = matcher.group(3);
        int common = commonPrefixLength(first, second);
        if (common < 2) {
            return text;
        }
        boolean genderTail = first.endsWith("男性") || first.endsWith("女性")
                || first.endsWith("男") || first.endsWith("女");
        if (common < 3 && !genderTail) {
            return text;
        }
        return second + text.substring(matcher.end());
    }

    /** 英文性别词换中文；前后已经有同一个中文词时直接删掉英文，避免叠成「男男」。 */
    private static String mapRoleWords(String text) {
        if (text.isEmpty()) {
            return text;
        }
        Matcher matcher = Pattern.compile(
                        "(?<![A-Za-z])(?:" + String.join("|", ROLE_WORDS.keySet()) + ")(?![A-Za-z])",
                        Pattern.CASE_INSENSITIVE)
                .matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String zh = ROLE_WORDS.get(matcher.group().toLowerCase(Locale.ROOT));
            char before = matcher.start() > 0 ? text.charAt(matcher.start() - 1) : '\0';
            char after = matcher.end() < text.length() ? text.charAt(matcher.end()) : '\0';
            if (before == zh.charAt(0) || after == zh.charAt(0)) {
                matcher.appendReplacement(out, "");
            } else {
                matcher.appendReplacement(out, Matcher.quoteReplacement(zh));
            }
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static int commonPrefixLength(String a, String b) {
        int limit = Math.min(a.length(), b.length());
        int i = 0;
        while (i < limit && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }

    /** 收尾：压掉多余空白、去掉中文标点前后的空格。 */
    private static String tidy(String text) {
        String result = text.replaceAll("[ \\t]{2,}", " ");
        result = result.replaceAll(" +([，,、；;：:）】」』])", "$1");
        result = result.replaceAll("([（【「『]) +", "$1");
        return result.strip();
    }
}
