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
}
