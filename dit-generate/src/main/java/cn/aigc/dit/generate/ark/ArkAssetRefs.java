package cn.aigc.dit.generate.ark;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.storage.ObjectStorage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;

/**
 * 图片旁边的方舟素材记录，和 Seedream {@code .origin.json} 一样跟 storage key 走。
 * 只有 {@code Active} 会变成 {@code asset://id}。
 */
public final class ArkAssetRefs {

    public static final String SUFFIX = ".ark-asset.json";

    private ArkAssetRefs() {
    }

    public record Ref(String assetId, String status, String error, String groupId) {

        public boolean active() {
            return "Active".equalsIgnoreCase(status);
        }

        public boolean processing() {
            return "Processing".equalsIgnoreCase(status);
        }
    }

    public static String sidecarKey(String storageKey) {
        return storageKey + SUFFIX;
    }

    public static void save(ObjectStorage storage, ObjectMapper mapper, String storageKey, Ref ref) {
        if (storage == null || mapper == null || !StringUtils.hasText(storageKey) || ref == null) {
            return;
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("assetId", ref.assetId() == null ? "" : ref.assetId().trim());
        body.put("status", ref.status() == null ? "" : ref.status().trim());
        body.put("error", ref.error() == null ? "" : ref.error().trim());
        body.put("groupId", ref.groupId() == null ? "" : ref.groupId().trim());
        try {
            storage.put(sidecarKey(storageKey), mapper.writeValueAsBytes(body), "application/json");
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL, "保存角色审核结果失败");
        }
    }

    public static Ref load(ObjectStorage storage, ObjectMapper mapper, String storageKey) {
        if (storage == null || mapper == null || !StringUtils.hasText(storageKey)) {
            return null;
        }
        String sidecar = sidecarKey(storageKey);
        if (!storage.exists(sidecar)) {
            return null;
        }
        try {
            byte[] bytes = storage.getBytes(sidecar);
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            JsonNode node = mapper.readTree(new String(bytes, StandardCharsets.UTF_8));
            String assetId = node.path("assetId").asText("").trim();
            String status = node.path("status").asText("").trim();
            if (assetId.isEmpty() && status.isEmpty()) {
                return null;
            }
            String error = node.path("error").asText("").trim();
            String groupId = node.path("groupId").asText("").trim();
            return new Ref(assetId, status, error, groupId);
        } catch (RuntimeException | java.io.IOException e) {
            return null;
        }
    }

    /**
     * @return {@code asset://id}；没有记录、还在处理或已失败时返回 null
     */
    public static String activeUri(ObjectStorage storage, ObjectMapper mapper, String storageKey) {
        Ref ref = load(storage, mapper, storageKey);
        if (ref == null || !ref.active() || !StringUtils.hasText(ref.assetId())) {
            return null;
        }
        String assetId = ref.assetId().trim();
        if (assetId.regionMatches(true, 0, "asset://", 0, "asset://".length())) {
            return assetId;
        }
        return "asset://" + assetId;
    }

    public static void deleteSidecar(ObjectStorage storage, String storageKey) {
        if (storage == null || !StringUtils.hasText(storageKey)) {
            return;
        }
        String sidecar = sidecarKey(storageKey);
        if (storage.exists(sidecar)) {
            storage.delete(sidecar);
        }
    }
}
