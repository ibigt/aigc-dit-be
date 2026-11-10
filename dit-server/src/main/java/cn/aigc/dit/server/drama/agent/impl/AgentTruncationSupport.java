package cn.aigc.dit.server.drama.agent.impl;

import cn.aigc.dit.server.drama.text.TextChatResult;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * v2 各 Agent 共用的「输出被截断」兜底工具。
 *
 * <p>背景：v2 的每个 Agent 都是「一次请求 → 拿整份 JSON」，而模型输出预算有限
 * （剧本 / 语义归一化 / 资产 / 分镜都是整集级 JSON，几千到上万 token）。模型一旦把
 * max_tokens 用光，返回的就是被拦腰砍断的 JSON，反序列化必然失败。之前各 Agent 只是
 * 把同一个请求原样重发两次 —— 预算没变，模型必然再被截断一次，整条流水线就此终止，
 * 而同样的稿子走 v1 却能靠加预算与细拆拆出来。
 *
 * <p>这里统一四件事：截断判定、预算上限、围栏 JSON 解析、失败原因。
 * 各 Agent 只需自己实现「拆片后怎么合并」那一步。
 */
final class AgentTruncationSupport {

    /** 截断后允许加倍到的上限（与 v1 老链路的 MAX_JSON_TOKENS 一致）。 */
    static final int MAX_TOKENS_CEILING = 32768;

    /** 剥围栏后最多试几个花括号起点（说明文字里也可能带花括号）。 */
    private static final int MAX_BRACE_CANDIDATES = 8;

    private static final Logger log = LoggerFactory.getLogger(AgentTruncationSupport.class);

    private AgentTruncationSupport() {
    }

    /** 是不是被 max_tokens 截断了。 */
    static boolean truncatedByLength(String finishReason) {
        if (finishReason == null || finishReason.isBlank()) {
            return false;
        }
        String value = finishReason.trim().toLowerCase(Locale.ROOT);
        return "length".equals(value) || "max_tokens".equals(value);
    }

    /**
     * 反序列化模型输出；解析不出来返回 null（交由调用方按截断/非法 JSON 分流）。
     *
     * <p>容错顺序与 v1 老链路对齐，但比 v2 之前的「只认整个 content 就是一个 JSON」宽：
     * <ol>
     *   <li>原样严格解析；</li>
     *   <li>原样容错解析（注释、尾逗号、未加引号的字段名、单引号）；</li>
     *   <li>剥掉代码围栏与 JSON 前后的说明文字，取最外层花括号再解析一遍。</li>
     * </ol>
     * 第 3 条是补 deepseek 这类模型的：它们常在 JSON 前面写一段「好的，以下是分镜草稿：」，
     * v1 能靠取最外层花括号救回来，v2 之前却整份判死 —— 重试与拆片都救不回来
     * （每次都同样地解析不了），最后就停在「重试 2 次均未通过 schema 校验」。
     */
    static <T> T parseJson(ObjectMapper mapper, String content, Class<T> type) {
        if (content == null || content.isBlank()) {
            return null;
        }
        T value = tryParse(mapper, content, type);
        if (value != null) {
            return value;
        }
        // 剥围栏后逐个候选花括号起点试一遍：说明文字里可能也有花括号
        // （比如「步骤{1}如下：」），只认第一个起点会切出非法片段。
        String text = stripFence(content);
        int end = text.lastIndexOf('}');
        int from = 0;
        for (int attempt = 0; attempt < MAX_BRACE_CANDIDATES && end > 0; attempt++) {
            int start = text.indexOf('{', from);
            if (start < 0 || start > end) {
                break;
            }
            value = tryParse(mapper, text.substring(start, end + 1), type);
            if (value != null) {
                log.info("[agent] JSON 剥掉围栏/前后说明文字后解析成功 type={}", type.getSimpleName());
                return value;
            }
            from = start + 1;
        }
        return null;
    }

    /** 严格解析失败再容错解析；都失败返回 null。 */
    private static <T> T tryParse(ObjectMapper mapper, String content, Class<T> type) {
        try {
            return mapper.readValue(content, type);
        } catch (Exception strict) {
            try {
                T value = lenient(mapper).readValue(content, type);
                log.info("[agent] JSON 经容错解析成功 type={}", type.getSimpleName());
                return value;
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    /** 复制一份并打开容错开关：注释、尾逗号、未加引号的字段名、单引号。 */
    private static ObjectMapper lenient(ObjectMapper mapper) {
        return mapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(JsonParser.Feature.ALLOW_COMMENTS, true)
                .configure(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature(), true)
                .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
                .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);
    }

    /**
     * 在正文中定位 {@code "key": [ ... ]} 的数组（括号配对），优先返回非空数组。
     *
     * <p>用途是抢救：整份合同解析不出来时，某个数组字段可能本身还是好的
     * （比如章节那部分写坏了、镜头数组没坏），单独把它捞出来重拼一次就能用。
     */
    static String extractJsonArray(String content, String key) {
        if (content == null || content.isBlank() || key == null || key.isBlank()) {
            return null;
        }
        String text = stripFence(content);
        String quoted = "\"" + key + "\"";
        String empty = null;
        int from = 0;
        while (from < text.length()) {
            int keyAt = text.indexOf(quoted, from);
            if (keyAt < 0) {
                break;
            }
            String array = extractArrayAfter(text, keyAt + quoted.length());
            from = keyAt + quoted.length();
            if (array == null) {
                continue;
            }
            if ("[]".equals(array.replaceAll("\\s+", ""))) {
                if (empty == null) {
                    empty = array;
                }
                continue;
            }
            return array;
        }
        return empty;
    }

    /** 剥掉 markdown 代码围栏。 */
    private static String stripFence(String content) {
        if (content == null) {
            return "";
        }
        return content.strip()
                .replaceAll("^```[a-zA-Z]*", "")
                .replaceAll("```$", "")
                .strip();
    }

    /** 从 {@code "key"} 之后取出括号配对的数组文本。 */
    private static String extractArrayAfter(String text, int afterKey) {
        int colon = text.indexOf(':', afterKey);
        if (colon < 0) {
            return null;
        }
        int start = colon + 1;
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        if (start >= text.length() || text.charAt(start) != '[') {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    /** 这次为什么没解析出来 —— 写进失败原因，别再让「不符合 schema」背锅。 */
    static String invalidReason(TextChatResult resp, boolean truncated) {
        if (truncated) {
            return "模型写到一半就被输出上限截断了（内容没写完就停了）";
        }
        String content = resp.getContent();
        if (content == null || content.isBlank()) {
            return "模型返回了空内容";
        }
        return "模型返回的内容不是预期的 JSON 格式";
    }

    /** 解析失败时带一小段原始输出，便于现场排查模型到底回了什么（压成一行、限长）。 */
    static String headOf(String content) {
        if (content == null) {
            return "";
        }
        String flat = content.replace("\r", " ").replace("\n", " ");
        return flat.length() <= 300 ? flat : flat.substring(0, 300);
    }
}
