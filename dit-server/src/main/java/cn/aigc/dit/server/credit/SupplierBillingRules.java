package cn.aigc.dit.server.credit;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 供应商计费口径内置表——依据各供应商官方计费文档整理。
 *
 * <p>口径（怎么算量）由本表决定，管理端只读展示、不开放编辑，避免配错导致亏本；
 * 价格（算多少积分）仍然配置在模型定价 {@code pricing} 里，两者职责分开。
 *
 * <p>本表是前后端唯一的口径来源：服务端在 {@code /api/models}、{@code /api/admin/models}
 * 下发模型时已把生效口径写进 {@code pricing.cjkWeight} / {@code pricing.cjkWeightLabel} /
 * {@code pricing.cjkWeightSource}，前端直接读取展示，不再自行推断。
 *
 * <p>接入新供应商时，先查其官方计费文档确认口径，再在本表登记一条——不要让运营在界面上猜。
 */
public final class SupplierBillingRules {

    /**
     * 语音字符计数口径。
     *
     * @param weight 1 个汉字算几个计费字符
     * @param label  口径说明，用于界面展示
     * @param source 官方依据，用于界面展示与追溯
     */
    public record CharacterRule(int weight, String label, String source) {
    }

    /** MiniMax：官方按输入文本的字符数计费，1 个汉字算 2 个字符。 */
    public static final CharacterRule MINIMAX_CHARACTER = new CharacterRule(2,
            "1 汉字 = 2 字符",
            "MiniMax 官方计费规则：按输入文本的字符数计费，1 个汉字算 2 个字符");

    /** 通用口径：按输入文本的字符数计费，1 字算 1 字符。 */
    public static final CharacterRule PLAIN_CHARACTER = new CharacterRule(1,
            "1 字 = 1 字符",
            "按输入文本的字符数计费，1 字算 1 字符");

    /**
     * 未登记供应商的兜底口径。当前唯一在用的语音供应商是 MiniMax，
     * 新接入供应商时应先查官方文档并在本表登记，避免沿用兜底值造成计费偏差。
     */
    public static final CharacterRule DEFAULT_CHARACTER = MINIMAX_CHARACTER;

    /** 适配协议 → 口径。 */
    private static final Map<String, CharacterRule> BY_ADAPTER = ordered(
            "minimax-speech", MINIMAX_CHARACTER,
            "openai-speech", PLAIN_CHARACTER);

    /** 供应商 → 口径（包含匹配，供应商名写法不一，如「火山引擎」）。 */
    private static final Map<String, CharacterRule> BY_PROVIDER = ordered(
            "minimax", MINIMAX_CHARACTER,
            "稀宇", MINIMAX_CHARACTER,
            "火山", PLAIN_CHARACTER,
            "volcengine", PLAIN_CHARACTER,
            "ark", PLAIN_CHARACTER,
            "doubao", PLAIN_CHARACTER);

    /** 模型名 → 口径（包含匹配）。 */
    private static final Map<String, CharacterRule> BY_MODEL = ordered(
            "speech-2.8-hd", MINIMAX_CHARACTER,
            "speech-2.8-turbo", MINIMAX_CHARACTER,
            "speech-2.6-hd", MINIMAX_CHARACTER,
            "speech-02-hd", MINIMAX_CHARACTER,
            "doubao-seed-tts", PLAIN_CHARACTER,
            "seed-speech", PLAIN_CHARACTER);

    private SupplierBillingRules() {
    }

    /**
     * 解析语音字符计数口径：适配协议 → 供应商 → 模型名 → 兜底。
     *
     * @param adapterKey 适配协议
     * @param provider   供应商
     * @param modelKey   模型名
     */
    public static CharacterRule resolveCharacterRule(String adapterKey, String provider, String modelKey) {
        CharacterRule rule = lookup(BY_ADAPTER, adapterKey);
        if (rule != null) {
            return rule;
        }
        rule = lookup(BY_PROVIDER, provider);
        if (rule != null) {
            return rule;
        }
        rule = lookup(BY_MODEL, modelKey);
        if (rule != null) {
            return rule;
        }
        return DEFAULT_CHARACTER;
    }

    /**
     * 包含匹配：登记的关键字出现在传入值里即命中。适配协议与供应商的名称写法不统一
     * （如供应商可能是「火山引擎」「火山方舟」），用包含匹配比精确匹配更贴近实际。
     */
    private static CharacterRule lookup(Map<String, CharacterRule> table, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String needle = value.trim().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, CharacterRule> entry : table.entrySet()) {
            if (needle.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** 按登记顺序建表，保证更具体的规则先命中。 */
    private static Map<String, CharacterRule> ordered(Object... pairs) {
        Map<String, CharacterRule> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            String key = String.valueOf(pairs[i]).toLowerCase(Locale.ROOT);
            map.put(key, (CharacterRule) pairs[i + 1]);
        }
        return map;
    }
}
