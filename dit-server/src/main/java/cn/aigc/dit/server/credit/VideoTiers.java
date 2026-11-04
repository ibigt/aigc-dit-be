package cn.aigc.dit.server.credit;

import java.util.Locale;

/**
 * 视频分辨率档位（480P / 720P / 768P / 1080P / 2K / 4K）与定价档位名的互转。
 *
 * <p>参考视频（r2v 的视频输入）按「它自己的档位」计价时用它把上游产物的分辨率、
 * 或素材库里上传视频的宽高，归到与定价 sale.tiers 同一个档位名。
 */
public final class VideoTiers {

    /** 档位从低到高：多段参考视频取最贵的一档时用它比大小。 */
    private static final String[] ORDER = {"480P", "720P", "768P", "1080P", "2K", "4K"};

    private VideoTiers() {
    }

    /**
     * 上游分辨率名（480p / 1080P / 2K …）归一到定价档位名；认不出来返回 null。
     * 图片档（1K/2K/4K 之外的清晰度名）不在此列，返回 null。
     */
    public static String normalize(String resolution) {
        if (resolution == null) {
            return null;
        }
        String value = resolution.trim().toUpperCase(Locale.ROOT);
        if (value.isEmpty()) {
            return null;
        }
        for (String tier : ORDER) {
            if (tier.equals(value)) {
                return tier;
            }
        }
        return null;
    }

    /**
     * 素材宽高按短边折档：16:9 的 480p（854×480）→480P、720p→720P、1080p→1080P，
     * 竖版取宽的一边同样成立；4K（2160 短边）以上归 4K。
     */
    public static String byShortSide(Integer width, Integer height) {
        if (width == null || height == null || width <= 0 || height <= 0) {
            return null;
        }
        int shortSide = Math.min(width, height);
        if (shortSide <= 480) {
            return "480P";
        }
        if (shortSide <= 720) {
            return "720P";
        }
        if (shortSide <= 768) {
            return "768P";
        }
        if (shortSide <= 1080) {
            return "1080P";
        }
        if (shortSide <= 1440) {
            return "2K";
        }
        return "4K";
    }

    /** 多段参考视频取更贵的一档；候选为 null 时保持原值。 */
    public static String highest(String current, String candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null) {
            return candidate;
        }
        return rank(candidate) > rank(current) ? candidate : current;
    }

    /** 档位序号（越小越便宜）；认不出来的档位按最贵处理。 */
    public static int rank(String tier) {
        for (int i = 0; i < ORDER.length; i++) {
            if (ORDER[i].equals(tier)) {
                return i;
            }
        }
        return -1;
    }
}
