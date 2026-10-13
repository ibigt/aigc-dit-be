package cn.aigc.dit.generate.ark;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.generate.GenerateProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 方舟素材库 OpenAPI：{@code POST https://ark.cn-beijing.volcengineapi.com/?Action=...&Version=2024-01-01}。
 */
@Component
public class ArkAssetClient implements ArkAssetApi {

    static final String DEFAULT_ENDPOINT = "https://ark.cn-beijing.volcengineapi.com";
    static final String DEFAULT_VERSION = "2024-01-01";
    static final String DEFAULT_REGION = "cn-beijing";
    static final String DEFAULT_SERVICE = "ark";

    public static final String MISSING_KEY_MESSAGE = "请先在模型管理中配置审核密钥";

    private final GenerateProperties.Ark properties;
    private final ArkAssetTransport transport;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ArkAssetCredentialSource credentials;

    @Autowired
    public ArkAssetClient(GenerateProperties properties, ObjectProvider<ArkAssetCredentialSource> credentials) {
        this(properties.getArk(), new JdkArkAssetTransport(properties.getArk()), new ObjectMapper(), Clock.systemUTC(),
                credentials.getIfAvailable());
    }

    ArkAssetClient(GenerateProperties.Ark properties, ArkAssetTransport transport, ObjectMapper objectMapper, Clock clock) {
        this(properties, transport, objectMapper, clock, null);
    }

    ArkAssetClient(GenerateProperties.Ark properties, ArkAssetTransport transport, ObjectMapper objectMapper,
                   Clock clock, ArkAssetCredentialSource credentials) {
        this.properties = properties;
        this.transport = transport;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.credentials = credentials;
    }

    @Override
    public String createAssetGroup(String name, String description, String groupType, String projectName) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("Name", name);
        body.put("Description", description);
        body.put("GroupType", groupType);
        body.put("ProjectName", projectName);
        return requireId(post("CreateAssetGroup", body).path("Result").path("Id").asText(""), "素材组 ID");
    }

    @Override
    public String createAsset(String groupId, String url, String name, String projectName) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("GroupId", groupId);
        body.put("URL", url);
        body.put("Name", name);
        body.put("AssetType", "Image");
        body.put("ProjectName", projectName);
        return requireId(post("CreateAsset", body).path("Result").path("Id").asText(""), "素材 ID");
    }

    @Override
    public ArkAssetView getAsset(String assetId, String projectName) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("Id", assetId);
        body.put("ProjectName", projectName);
        JsonNode result = post("GetAsset", body).path("Result");
        if (result.isMissingNode() || result.isNull()) {
            throw new BizException(ErrorCode.INTERNAL, "方舟素材库未返回素材");
        }
        JsonNode error = result.path("Error");
        return new ArkAssetView(
                result.path("Id").asText(assetId),
                result.path("Status").asText(""),
                text(error, "Code"),
                text(error, "Message"));
    }

    @Override
    public void deleteAsset(String assetId, String projectName) {
        if (!StringUtils.hasText(assetId)) {
            return;
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("Id", assetId.trim());
        body.put("ProjectName", projectName);
        postAllowingMissing("DeleteAsset", body);
    }

    private JsonNode post(String action, ObjectNode body) {
        ArkAssetHttpResult result = send(action, body);
        if (result.status() < 200 || result.status() >= 300) {
            throw httpError(result);
        }
        JsonNode root = read(result.body());
        String metadataError = ArkAssetErrors.fromBody(result.body());
        if (metadataError != null && root.path("ResponseMetadata").has("Error")) {
            JsonNode error = root.path("ResponseMetadata").path("Error");
            if (text(error, "Code") != null || text(error, "Message") != null) {
                throw new BizException(ErrorCode.BAD_REQUEST, metadataError);
            }
        }
        return root;
    }

    private void postAllowingMissing(String action, ObjectNode body) {
        ArkAssetHttpResult result = send(action, body);
        if (result.status() >= 200 && result.status() < 300) {
            String metadataError = ArkAssetErrors.fromBody(result.body());
            if (metadataError != null && !ArkAssetErrors.missingAsset(result.body())) {
                JsonNode error = read(result.body()).path("ResponseMetadata").path("Error");
                if (text(error, "Code") != null || text(error, "Message") != null) {
                    throw new BizException(ErrorCode.BAD_REQUEST, metadataError);
                }
            }
            return;
        }
        if (ArkAssetErrors.missingAsset(result.body())) {
            return;
        }
        throw httpError(result);
    }

    private ArkAssetHttpResult send(String action, ObjectNode body) {
        assertCredentials();
        byte[] payload;
        try {
            payload = objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL, "组装方舟素材库请求失败");
        }
        URI uri = endpoint(action);
        String accessKey = resolveAccessKey();
        String secretKey = resolveSecretKey();
        if (!StringUtils.hasText(accessKey) || !StringUtils.hasText(secretKey)) {
            throw new BizException(ErrorCode.BAD_REQUEST, MISSING_KEY_MESSAGE);
        }
        ArkAssetSigner.Signed signed = ArkAssetSigner.sign(
                accessKey,
                secretKey,
                region(),
                service(),
                uri,
                payload,
                clock.instant());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");
        headers.put("x-date", signed.xDate());
        headers.put("x-content-sha256", signed.contentSha256());
        headers.put("authorization", signed.authorization());
        return transport.post(uri, headers, payload);
    }

    private URI endpoint(String action) {
        String base = StringUtils.hasText(properties.getAssetEndpoint())
                ? properties.getAssetEndpoint().trim()
                : DEFAULT_ENDPOINT;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String version = StringUtils.hasText(properties.getAssetApiVersion())
                ? properties.getAssetApiVersion().trim()
                : DEFAULT_VERSION;
        return URI.create(base + "/?Action=" + action + "&Version=" + version);
    }

    private void assertCredentials() {
        if (!StringUtils.hasText(resolveAccessKey()) || !StringUtils.hasText(resolveSecretKey())) {
            throw new BizException(ErrorCode.BAD_REQUEST, MISSING_KEY_MESSAGE);
        }
    }

    private String resolveAccessKey() {
        return firstConfigured(credentials == null ? null : credentials.accessKey(), properties.getAccessKey());
    }

    private String resolveSecretKey() {
        return firstConfigured(credentials == null ? null : credentials.secretKey(), properties.getSecretKey());
    }

    private static String firstConfigured(String preferred, String fallback) {
        if (StringUtils.hasText(preferred)) {
            return preferred.trim();
        }
        return StringUtils.hasText(fallback) ? fallback.trim() : "";
    }

    private String region() {
        return StringUtils.hasText(properties.getAssetRegion()) ? properties.getAssetRegion().trim() : DEFAULT_REGION;
    }

    private String service() {
        return StringUtils.hasText(properties.getAssetService()) ? properties.getAssetService().trim() : DEFAULT_SERVICE;
    }

    private JsonNode read(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL, "解析方舟素材库响应失败");
        }
    }

    private static BizException httpError(ArkAssetHttpResult result) {
        String original = ArkAssetErrors.fromBody(result.body());
        if (original != null) {
            return new BizException(ErrorCode.BAD_REQUEST, original);
        }
        return new BizException(ErrorCode.BAD_REQUEST, "方舟素材库请求失败 HTTP " + result.status());
    }

    private static String requireId(String id, String what) {
        if (id == null || id.isBlank() || id.chars().anyMatch(Character::isWhitespace)) {
            throw new BizException(ErrorCode.INTERNAL, "方舟素材库未返回" + what);
        }
        return id.trim();
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        String value = node.get(field).asText("");
        return value.isBlank() ? null : value.trim();
    }
}
