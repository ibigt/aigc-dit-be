package cn.aigc.dit.server.credit;

import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PricingPolicy {
    private static final Pattern AMOUNT = Pattern.compile("(-?\\d+(?:\\.\\d+)?)");
    private static final Map<String, List<String>> UNITS = Map.of(
            ModelCategory.IMAGE, List.of("张", "次"),
            ModelCategory.VIDEO, List.of("秒", "次"),
            ModelCategory.AUDIO, List.of("千字符", "万字符", "次"),
            ModelCategory.TEXT, List.of("千Token", "百万Token", "次"));

    private PricingPolicy() {
    }

    public static void validate(String category, Map<String, Object> pricing) {
        if (pricing == null || pricing.isEmpty()) return;
        Object unit = pricing.get("unit");
        List<String> allowed = UNITS.getOrDefault(category, List.of("次"));
        if (unit != null && !allowed.contains(String.valueOf(unit).trim())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "计费单位与模型类型不匹配");
        }
        validateSide("售价", pricing.get("sale"));
        validateSide("成本", pricing.get("cost"));
        validateCjkWeight(pricing);
        validateCapabilityUnifiedPrice(category, pricing);
    }

    /** 1 元 = 10 积分（充值口径）。 */
    static final double CREDITS_PER_YUAN = 10D;

    /**
     * 能力统一价的不亏本校验：能力之间不分价——同一模型的所有能力共用同一套「分辨率分档价」，
     * 所以任一档的售价都必须覆盖该档成本，否则用户无论选哪个能力我们都会亏本。
     */
    private static void validateCapabilityUnifiedPrice(String category, Map<String, Object> pricing) {
        Object saleSide = pricing.get("sale");
        Object costSide = pricing.get("cost");
        if (!(saleSide instanceof Map<?, ?> sale) || !(costSide instanceof Map<?, ?> cost)) {
            return;
        }
        Object saleTiers = sale.get("tiers");
        Object costTiers = cost.get("tiers");
        Map<?, ?> saleTierMap = saleTiers instanceof Map<?, ?> map ? map : Map.of();
        Map<?, ?> costTierMap = costTiers instanceof Map<?, ?> map ? map : Map.of();
        if (!saleTierMap.isEmpty()) {
            for (Map.Entry<?, ?> entry : saleTierMap.entrySet()) {
                Double saleAmount = parseAmount(entry.getValue());
                Double costAmount = parseAmount(costTierMap.get(entry.getKey()));
                if (saleAmount == null || costAmount == null || costAmount <= 0) {
                    continue;
                }
                if (saleAmount / CREDITS_PER_YUAN < costAmount) {
                    throw new BizException(ErrorCode.BAD_REQUEST,
                            entry.getKey() + " 档售价 " + trimAmount(saleAmount) + " 积分（约 ¥"
                                    + trimAmount(saleAmount / CREDITS_PER_YUAN) + "）低于成本 ¥"
                                    + trimAmount(costAmount) + "，会亏本");
                }
            }
            return;
        }
        Double saleAmount = parseAmount(sale.get("output"));
        Double costAmount = parseAmount(cost.get("output"));
        if (saleAmount == null || costAmount == null || costAmount <= 0) {
            return;
        }
        if (saleAmount / CREDITS_PER_YUAN < costAmount) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "售价 " + trimAmount(saleAmount) + " 积分（约 ¥" + trimAmount(saleAmount / CREDITS_PER_YUAN)
                            + "）低于成本 ¥" + trimAmount(costAmount) + "，会亏本");
        }
    }

    private static String trimAmount(double value) {
        return String.valueOf(Math.round(value * 100D) / 100D);
    }

    /**
     * 语音字符计数的汉字权重：1 = 1 字 = 1 字符（火山/Ark 等），2 = 1 汉字 = 2 字符（MiniMax）。
     * 缺省按供应商推断，不强制配置；配了必须落在 1 或 2。
     */
    private static void validateCjkWeight(Map<String, Object> pricing) {
        Object raw = pricing.get("cjkWeight");
        if (raw == null || String.valueOf(raw).isBlank()) {
            return;
        }
        double weight;
        if (raw instanceof Number number) {
            weight = number.doubleValue();
        } else {
            try {
                weight = Double.parseDouble(String.valueOf(raw).trim());
            } catch (NumberFormatException e) {
                throw new BizException(ErrorCode.BAD_REQUEST, "汉字计费口径必须是 1 或 2");
            }
        }
        if (weight != 1D && weight != 2D) {
            throw new BizException(ErrorCode.BAD_REQUEST, "汉字计费口径必须是 1 或 2");
        }
    }

    private static void validateSide(String label, Object side) {
        if (side == null) return;
        if (side instanceof Map<?, ?> values) {
            validateAmount(label, values.get("input"));
            validateAmount(label, values.get("output"));
            validateAmount(label, values.get("cache"));
            return;
        }
        validateAmount(label, side);
    }

    private static void validateAmount(String label, Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) return;
        double amount;
        if (raw instanceof Number number) {
            amount = number.doubleValue();
        } else {
            Matcher matcher = AMOUNT.matcher(String.valueOf(raw));
            if (!matcher.find()) {
                throw new BizException(ErrorCode.BAD_REQUEST, label + "必须是有效数字");
            }
            amount = Double.parseDouble(matcher.group(1));
        }
        if (!Double.isFinite(amount) || amount < 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "不能为负数");
        }
    }

    private static Double parseAmount(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) return null;
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        Matcher matcher = AMOUNT.matcher(String.valueOf(raw));
        if (!matcher.find()) return null;
        return Double.parseDouble(matcher.group(1));
    }
}
