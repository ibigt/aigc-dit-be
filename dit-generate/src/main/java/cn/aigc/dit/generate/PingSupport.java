package cn.aigc.dit.generate;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 连通性测试共用规则：只做零消耗探活，按统一约定分类响应。
 * <p>各 Jdk Http 对非 2xx 响应抛出的 {@link BizException} 消息固定含 {@code HTTP nnn}，
 * 以此区分“服务端已响应”（可按状态码判断鉴权结果）与“连接不上/超时”（原样抛连接失败）。</p>
 */
public final class PingSupport {

    /** 探活用的占位任务号：查询一个不存在的任务只验证鉴权与链路，不产生任何费用。 */
    public static final String PROBE_TASK_ID = "dit-connectivity-probe";

    private static final Pattern HTTP_STATUS = Pattern.compile("HTTP (\\d{3})");
    private static final Pattern AUTH_FAILURE = Pattern.compile(
            "api\\s*key|apikey|token|鉴权|授权|认证|unauthorized|forbidden|permission", Pattern.CASE_INSENSITIVE);
    private static final Pattern MODEL_FAILURE = Pattern.compile(
            "model.*(not|invalid|unknown|unsupported)|(?:不|未|无)(?:支持|找到|存在).*模[型型号]|模型(?:不|未|无)|invalid model|unknown model|no such model",
            Pattern.CASE_INSENSITIVE);

    private PingSupport() {
    }

    /** 从 {@link BizException} 消息提取服务端 HTTP 状态码；连接不上/超时返回 {@code null}。 */
    public static Integer httpStatusOf(BizException error) {
        if (error == null || error.getMessage() == null) {
            return null;
        }
        Matcher matcher = HTTP_STATUS.matcher(error.getMessage());
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    public static boolean isUnauthorized(Integer status) {
        return status != null && (status == 401 || status == 403);
    }

    /** 查询类接口的通用分类：401/403 = Key 无效（抛出）；其他 HTTP 状态 = 链路与鉴权通过（通过）；无状态码 = 连接失败（抛出）。 */
    public static void classifyQueryProbe(BizException error) {
        Integer status = httpStatusOf(error);
        if (status == null || isUnauthorized(status)) {
            throw error;
        }
    }

    /** 响应体文案是否指向 API Key / 鉴权问题。 */
    public static boolean looksLikeAuthFailure(String message) {
        return message != null && AUTH_FAILURE.matcher(message).find();
    }

    /** 响应体文案是否指向模型标识问题。 */
    public static boolean looksLikeModelFailure(String message) {
        return message != null && MODEL_FAILURE.matcher(message).find();
    }

    /**
     * 解析 OpenAI 兼容 {@code GET /models} 响应中的模型 ID 列表；
     * 结构不可识别时返回 {@code null}（不作为失败，仅跳过模型校验）。
     */
    public static List<String> parseModelIds(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode data = new ObjectMapper().readTree(body).path("data");
            if (!data.isArray() || data.isEmpty()) {
                return null;
            }
            List<String> ids = new ArrayList<>();
            for (JsonNode item : data) {
                String id = item.path("id").asText("");
                if (!id.isBlank()) {
                    ids.add(id);
                }
            }
            return ids.isEmpty() ? null : ids;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 服务端返回了模型列表且不含当前模型标识时判定失败。 */
    public static void assertModelListed(List<String> modelIds, String modelKey) {
        if (modelIds == null || modelKey == null || modelKey.isBlank()) {
            return;
        }
        boolean listed = modelIds.stream().anyMatch(id -> id.equalsIgnoreCase(modelKey));
        if (!listed) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "模型标识 " + modelKey + " 不在服务端模型列表中，请核对模型标识（共 " + modelIds.size() + " 个可用模型）");
        }
    }
}
