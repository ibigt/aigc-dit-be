package cn.aigc.dit.server.drama;

import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 角色版本：slug、展示标签、分镜 mention（语法 A：{@code 名} / {@code 名·标签}）。
 */
public final class DramaAssetVersions {

    public static final char MENTION_SEP = '·';
    public static final int VERSION_LABEL_MAX = 16;
    private static final Pattern FORBIDDEN_IN_LABEL = Pattern.compile("[@·・\\s]");

    private DramaAssetVersions() {
    }

    /** 分镜引用名：基础版或无版本标签时为短名；有 versionLabel 时为「名·标签」。 */
    public static String mentionName(String familyName, String stateTag, String versionLabel) {
        String base = familyName == null ? "" : familyName.trim();
        if (!StringUtils.hasText(base)) {
            return "";
        }
        // 无展示标签时保持短名（兼容旧「梦魇」等独立角色虽带 dream 标签）。
        if (isBase(stateTag) || !StringUtils.hasText(versionLabel)) {
            return base;
        }
        return base + MENTION_SEP + versionLabel.trim();
    }

    public static String mentionNameOf(cn.aigc.dit.server.drama.entity.DramaAsset asset) {
        if (asset == null) {
            return "";
        }
        return mentionName(asset.getName(), asset.getStateTag(), asset.getVersionLabel());
    }

    public static boolean isBase(String stateTag) {
        return !StringUtils.hasText(stateTag)
                || DramaContract.STATE_TAG_BASE.equalsIgnoreCase(stateTag.trim());
    }

    /** 展示用版本标签；无 versionLabel 时一律「基础」（兼容旧独立 dream 角色）。 */
    public static String displayLabel(String stateTag, String versionLabel) {
        if (isBase(stateTag) || !StringUtils.hasText(versionLabel)) {
            return "基础";
        }
        return versionLabel.trim();
    }

    public static void validateVersionLabel(String label) {
        if (!StringUtils.hasText(label)) {
            throw new cn.aigc.dit.common.exception.BizException(
                    cn.aigc.dit.common.exception.ErrorCode.BAD_REQUEST, "请填写版本标签");
        }
        String value = label.trim();
        if (value.length() > VERSION_LABEL_MAX) {
            throw new cn.aigc.dit.common.exception.BizException(
                    cn.aigc.dit.common.exception.ErrorCode.BAD_REQUEST,
                    "版本标签不能超过 " + VERSION_LABEL_MAX + " 字");
        }
        if (FORBIDDEN_IN_LABEL.matcher(value).find()) {
            throw new cn.aigc.dit.common.exception.BizException(
                    cn.aigc.dit.common.exception.ErrorCode.BAD_REQUEST,
                    "版本标签不能包含 @、间隔号或空白");
        }
        if ("基础".equals(value)) {
            throw new cn.aigc.dit.common.exception.BizException(
                    cn.aigc.dit.common.exception.ErrorCode.BAD_REQUEST, "「基础」为保留标签");
        }
    }

    public static void validateFamilyName(String name) {
        if (!StringUtils.hasText(name)) {
            throw new cn.aigc.dit.common.exception.BizException(
                    cn.aigc.dit.common.exception.ErrorCode.BAD_REQUEST, "资产名称不能为空");
        }
        String value = name.trim();
        if (value.indexOf('·') >= 0 || value.indexOf('・') >= 0) {
            throw new cn.aigc.dit.common.exception.BizException(
                    cn.aigc.dit.common.exception.ErrorCode.BAD_REQUEST,
                    "角色名不能包含间隔号，版本请用标签区分");
        }
    }

    /**
     * 从版本标签生成 slug。已知词映射到 dream/costume/wet；其余 ASCII 短标识或哈希。
     */
    public static String slugFromLabel(String label) {
        if (!StringUtils.hasText(label)) {
            return DramaContract.STATE_TAG_CUSTOM;
        }
        String value = label.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (value.contains("入梦") || value.contains("梦境") || value.contains("梦魇") || lower.contains("dream")) {
            return DramaContract.STATE_TAG_DREAM;
        }
        if (value.contains("换装") || lower.contains("costume")) {
            return DramaContract.STATE_TAG_COSTUME;
        }
        if (value.contains("湿身") || lower.contains("wet")) {
            return DramaContract.STATE_TAG_WET;
        }
        String ascii = lower.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (StringUtils.hasText(ascii) && ascii.length() <= 32 && ascii.matches("[a-z0-9].*")) {
            if (DramaContract.STATE_TAG_BASE.equals(ascii)) {
                return "v-base";
            }
            return ascii;
        }
        return "v" + shortHash(value);
    }

    /** 同族 slug 冲突时追加 -2、-3… */
    public static String uniquifySlug(String preferred, java.util.Set<String> used) {
        String base = StringUtils.hasText(preferred) ? preferred.trim().toLowerCase(Locale.ROOT) : "v";
        if (used == null || !used.contains(base)) {
            return base;
        }
        for (int i = 2; i < 1000; i++) {
            String candidate = base + "-" + i;
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
        return base + "-" + shortHash(base + System.nanoTime());
    }

    /**
     * 剧本/旧数据里的「洛白·入梦」拆成族名 + 标签。无间隔号时标签空。
     */
    public static ParsedName parseLegacyName(String raw) {
        if (!StringUtils.hasText(raw)) {
            return new ParsedName("", "", DramaContract.STATE_TAG_BASE);
        }
        String trimmed = raw.trim();
        int dot = trimmed.indexOf('·');
        if (dot < 0) {
            dot = trimmed.indexOf('・');
        }
        if (dot <= 0 || dot >= trimmed.length() - 1) {
            return new ParsedName(trimmed, "", DramaContract.STATE_TAG_BASE);
        }
        String family = trimmed.substring(0, dot).trim();
        String label = trimmed.substring(dot + 1).trim();
        return new ParsedName(family, label, slugFromLabel(label));
    }

    public static String shortHash(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode()).replace("-", "n");
        }
    }

    public record ParsedName(String familyName, String versionLabel, String stateTag) {
    }
}
