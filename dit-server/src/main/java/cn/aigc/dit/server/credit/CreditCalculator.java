package cn.aigc.dit.server.credit;

import cn.aigc.dit.common.constant.ModelCategory;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 服务端积分：ceil(售价 × 参数倍率 × 副本)，视频且单位为秒时再乘时长。不信前端。
 */
public final class CreditCalculator {

    private static final Pattern NUMBER = Pattern.compile("(-?\\d+(?:\\.\\d+)?)");

    private CreditCalculator() {
    }

    public static long calculate(Map<String, Object> pricing, String category,
                                 String resolution, String quality,
                                 Integer durationSec, int copies) {
        Double configuredPrice = saleOutput(pricing);
        double unitPrice = configuredPrice == null
                ? (ModelCategory.VIDEO.equalsIgnoreCase(category) ? 12D
                    : ModelCategory.AUDIO.equalsIgnoreCase(category) ? 3D : 2D)
                : Math.max(0D, configuredPrice);
        double multiplier = parameterMultiplier(category, resolution, quality);
        double quantity = unitPrice;
        if (ModelCategory.VIDEO.equalsIgnoreCase(category) && isPerSecond(pricing)) {
            int duration = durationSec == null ? 1 : Math.max(1, durationSec);
            quantity = unitPrice * duration;
        }
        if (ModelCategory.AUDIO.equalsIgnoreCase(category)) {
            quantity = unitPrice * characterUnits(pricing, durationSec);
        }
        int safeCopies = Math.min(4, Math.max(1, copies));
        return (long) Math.ceil(quantity * multiplier * safeCopies);
    }

    /**
     * 文本模型按供应商实际 usage 结算。缓存 Token 是输入 Token 的子集，需先从普通输入中扣除，
     * 避免同时按输入价和缓存价重复计费。
     */
    public static long calculateText(Map<String, Object> pricing,
                                     long promptTokens, long completionTokens, long cachedPromptTokens) {
        if (pricing == null) return 0L;
        Object sale = pricing.get("sale");
        if (!(sale instanceof Map<?, ?> prices)) {
            Double flat = parseAmount(sale);
            return flat == null ? 0L : (long) Math.ceil(Math.max(0D, flat));
        }
        String unit = pricing.get("unit") == null ? "" : String.valueOf(pricing.get("unit")).trim();
        if ("次".equals(unit)) {
            return (long) Math.ceil(nonNegativeAmount(prices.get("output")));
        }
        double divisor = "百万Token".equals(unit) ? 1_000_000D : 1_000D;
        long safePrompt = Math.max(0L, promptTokens);
        long safeCompletion = Math.max(0L, completionTokens);
        long safeCached = Math.min(safePrompt, Math.max(0L, cachedPromptTokens));
        long billableInput = safePrompt - safeCached;
        double credits = billableInput / divisor * nonNegativeAmount(prices.get("input"))
                + safeCompletion / divisor * nonNegativeAmount(prices.get("output"))
                + safeCached / divisor * nonNegativeAmount(prices.get("cache"));
        return (long) Math.ceil(credits);
    }

    private static double nonNegativeAmount(Object value) {
        Double amount = parseAmount(value);
        return amount == null ? 0D : Math.max(0D, amount);
    }

    static double parameterMultiplier(String category, String resolution, String quality) {
        String res = resolution == null ? "" : resolution.toUpperCase(Locale.ROOT);
        if (ModelCategory.VIDEO.equalsIgnoreCase(category)) {
            return switch (res) {
                case "480P" -> 0.5;
                case "768P" -> 0.5;
                case "720P" -> 0.75;
                case "1080P" -> 1.0;
                case "2K" -> 1.5;
                case "4K" -> 2.5;
                default -> 1.0;
            };
        }
        double resMul = switch (res) {
            case "2K" -> 2.0;
            case "4K" -> 4.0;
            default -> 1.0;
        };
        String q = quality == null ? "LOW" : quality.toUpperCase(Locale.ROOT);
        double qualityMul = switch (q) {
            case "MEDIUM" -> 1.5;
            case "HIGH" -> 2.0;
            default -> 1.0;
        };
        return resMul * qualityMul;
    }

    static Double saleOutput(Map<String, Object> pricing) {
        if (pricing == null) {
            return null;
        }
        Object sale = pricing.get("sale");
        if (sale instanceof Map<?, ?> saleMap) {
            return parseAmount(saleMap.get("output"));
        }
        return parseAmount(pricing.get("sale"));
    }

    static boolean isPerSecond(Map<String, Object> pricing) {
        if (pricing == null || pricing.get("unit") == null) {
            return false;
        }
        return "秒".equals(String.valueOf(pricing.get("unit")).trim());
    }

    /** audio 调用时 quantity 参数表示字符数；图片/视频仍表示时长。 */
    static double characterUnits(Map<String, Object> pricing, Integer characterCount) {
        int count = characterCount == null ? 1 : Math.max(1, characterCount);
        String unit = pricing == null || pricing.get("unit") == null ? "" : String.valueOf(pricing.get("unit")).trim();
        if ("万字符".equals(unit)) {
            return count / 10000D;
        }
        if ("千字符".equals(unit)) {
            return count / 1000D;
        }
        if ("字符".equals(unit)) {
            return count;
        }
        return 1D;
    }

    static Double parseAmount(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        Matcher matcher = NUMBER.matcher(String.valueOf(value));
        if (matcher.find()) {
            return Double.parseDouble(matcher.group(1));
        }
        return null;
    }
}
