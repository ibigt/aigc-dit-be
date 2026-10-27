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
        return calculate(pricing, category, resolution, quality, durationSec, copies, 0);
    }

    /**
     * 参考视频（r2v 的视频输入）秒数按模型<b>最高档价</b>计费，不按输出所选分辨率档价：
     * 上游中转把输入视频 token 也按最高档价扣我们的钱（官方直连的输入价更低），
     * 而参考视频本身的分辨率/码率由用户决定、无从得知——只能统一取最高标准，保证不亏本。
     * 能力之间不分价：同一模型的各生成能力共用这一套标准。
     */
    public static long calculate(Map<String, Object> pricing, String category,
                                 String resolution, String quality,
                                 Integer durationSec, int copies, int referenceVideoSeconds) {
        Double configuredPrice = saleOutput(pricing);
        double unitPrice = configuredPrice == null
                ? (ModelCategory.VIDEO.equalsIgnoreCase(category) ? 12D
                    : ModelCategory.AUDIO.equalsIgnoreCase(category) ? 3D : 2D)
                : Math.max(0D, configuredPrice);
        String res = resolution == null ? "" : resolution.toUpperCase(Locale.ROOT);
        Double tierPrice = tierPrice(pricing, res);
        // 分档价优先：有该档价格直接用，缺档回退 基准价 × 分辨率倍率；图片清晰度倍率始终叠加。
        double perUnit = tierPrice == null
                ? unitPrice * resolutionMultiplier(category, res)
                : Math.max(0D, tierPrice);
        double quantity = perUnit;
        if (ModelCategory.VIDEO.equalsIgnoreCase(category) && isPerSecond(pricing)) {
            int duration = durationSec == null ? 1 : Math.max(1, durationSec);
            int safeCopies = Math.min(4, Math.max(1, copies));
            // 输出部分按所选分辨率档价 × 副本数。
            quantity = perUnit * duration * safeCopies;
            // 参考视频（r2v 的视频输入）按模型最高档价计一次（同一份输入多副本不重复计）：
            // 上游把输入视频 token 也按最高档价扣我们的钱，参考视频本身规格又由用户决定、
            // 无从得知——统一取最高标准，保证任何能力组合都不亏本。
            if (referenceVideoSeconds > 0) {
                quantity += highestUnitPrice(pricing, unitPrice) * referenceVideoSeconds;
            }
            return (long) Math.ceil(quantity * qualityMultiplier(category, quality));
        }
        if (ModelCategory.AUDIO.equalsIgnoreCase(category)) {
            quantity = unitPrice * characterUnits(pricing, durationSec);
        }
        int safeCopies = Math.min(4, Math.max(1, copies));
        return (long) Math.ceil(quantity * qualityMultiplier(category, quality) * safeCopies);
    }

    /**
     * 模型最高档单价（每单位）：取 sale.tiers 中的最高档价与基准价里的较大者。
     * 能力之间不分价：所有能力共用这一套标准，参考视频按最高档计。
     */
    static double highestUnitPrice(Map<String, Object> pricing, double fallbackUnitPrice) {
        double highest = Math.max(0D, fallbackUnitPrice);
        Object sale = pricing == null ? null : pricing.get("sale");
        if (sale instanceof Map<?, ?> saleMap && saleMap.get("tiers") instanceof Map<?, ?> tiers) {
            for (Object value : tiers.values()) {
                Double amount = parseAmount(value);
                if (amount != null && amount > highest) {
                    highest = amount;
                }
            }
        }
        return highest;
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
        return resolutionMultiplier(category, res) * qualityMultiplier(category, quality);
    }

    static double resolutionMultiplier(String category, String res) {
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
        return switch (res) {
            case "2K" -> 2.0;
            case "4K" -> 4.0;
            default -> 1.0;
        };
    }

    static double qualityMultiplier(String category, String quality) {
        if (!ModelCategory.IMAGE.equalsIgnoreCase(category)) {
            return 1.0;
        }
        String q = quality == null ? "LOW" : quality.toUpperCase(Locale.ROOT);
        return switch (q) {
            case "MEDIUM" -> 1.5;
            case "HIGH" -> 2.0;
            default -> 1.0;
        };
    }

    /** sale.tiers 中该分辨率档的分档价；无则返回 null（回退基准价 × 全局倍率）。 */
    static Double tierPrice(Map<String, Object> pricing, String resolution) {
        if (pricing == null || resolution == null || resolution.isEmpty()) {
            return null;
        }
        Object sale = pricing.get("sale");
        if (sale instanceof Map<?, ?> saleMap && saleMap.get("tiers") instanceof Map<?, ?> tiers) {
            return parseAmount(tiers.get(resolution));
        }
        return null;
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

    /**
     * 语音计费字符数的默认汉字权重：MiniMax 口径 1 汉字 = 2 字符。
     * 按「1 字 = 1 字符」计费的供应商（火山/Ark 等）需要在模型定价里配置 {@code cjkWeight = 1}。
     */
    public static final int DEFAULT_CJK_WEIGHT = 2;

    /**
     * 语音计费字符数：与供应商口径一致——汉字（CJK）按 cjkWeight 计（默认 2），
     * 英文字母/数字/标点/空格各算 1。必须与前端 pricing.js 的 billableCharacters 保持一致，
     * 否则估价与实扣不符。
     */
    public static int billableCharacters(String text, int cjkWeight) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int weight = Math.max(1, cjkWeight);
        int total = 0;
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            total += isCjk(codePoint) ? weight : 1;
            index += Character.charCount(codePoint);
        }
        return total;
    }

    /**
     * 汉字计费权重：优先取模型定价里配置的 {@code cjkWeight}（1 或 2）——这是给中转渠道等
     * 特殊情况预留的模型级覆盖；缺省查供应商计费口径内置表 {@link SupplierBillingRules}。
     */
    public static int cjkWeight(Map<String, Object> pricing, String adapterKey, String provider, String modelKey) {
        Double configured = parseAmount(pricing == null ? null : pricing.get("cjkWeight"));
        if (configured != null && configured >= 1D && configured <= 2D) {
            return (int) Math.round(configured);
        }
        return SupplierBillingRules.resolveCharacterRule(adapterKey, provider, modelKey).weight();
    }

    /**
     * 生效的语音字符计数口径（含展示用的说明与官方依据）。
     * 模型级 {@code cjkWeight} 覆盖在内置表之上，界面按这里的结果展示。
     */
    public static SupplierBillingRules.CharacterRule characterRule(Map<String, Object> pricing,
                                                                  String adapterKey, String provider, String modelKey) {
        SupplierBillingRules.CharacterRule builtin =
                SupplierBillingRules.resolveCharacterRule(adapterKey, provider, modelKey);
        Double configured = parseAmount(pricing == null ? null : pricing.get("cjkWeight"));
        if (configured != null && configured >= 1D && configured <= 2D) {
            int weight = (int) Math.round(configured);
            if (weight != builtin.weight()) {
                return new SupplierBillingRules.CharacterRule(weight,
                        weight == 1 ? "1 字 = 1 字符" : "1 汉字 = 2 字符",
                        "模型级覆盖（内置规则为 " + builtin.label() + "）");
            }
        }
        return builtin;
    }

    private static boolean isCjk(int codePoint) {
        // 仅汉字表意文字算 2；标点（含全角标点）、字母、数字、空格等一律算 1
        return (codePoint >= 0x4E00 && codePoint <= 0x9FFF)      // CJK 统一表意文字
                || (codePoint >= 0x3400 && codePoint <= 0x4DBF)  // 扩展 A
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF); // 兼容表意文字
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
