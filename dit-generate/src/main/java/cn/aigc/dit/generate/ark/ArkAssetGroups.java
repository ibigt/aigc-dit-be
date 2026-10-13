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
 * 整个环境只建一次的 AIGC 素材组。配置里没有 GroupId 时，把创建结果写到对象存储，重启后继续用。
 */
public final class ArkAssetGroups {

    public static final String STORAGE_KEY = "ark/aigc-dit-asset-group.json";
    public static final String NAME = "aigc-dit";
    public static final String DESCRIPTION = "aigc-dit 虚拟人像";
    public static final String TYPE = "AIGC";

    private ArkAssetGroups() {
    }

    public static String read(ObjectStorage storage, ObjectMapper mapper, String projectName) {
        if (storage == null || mapper == null || !storage.exists(STORAGE_KEY)) {
            return null;
        }
        try {
            byte[] bytes = storage.getBytes(STORAGE_KEY);
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            JsonNode node = mapper.readTree(new String(bytes, StandardCharsets.UTF_8));
            String groupId = node.path("groupId").asText("").trim();
            String storedProject = node.path("projectName").asText("").trim();
            String expected = projectName == null ? "" : projectName.trim();
            if (!StringUtils.hasText(groupId) || !storedProject.equals(expected)) {
                return null;
            }
            return groupId;
        } catch (RuntimeException | java.io.IOException e) {
            return null;
        }
    }

    public static void write(ObjectStorage storage, ObjectMapper mapper, String groupId, String projectName) {
        if (storage == null || mapper == null || !StringUtils.hasText(groupId)) {
            throw new BizException(ErrorCode.INTERNAL, "保存方舟素材组失败");
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("groupId", groupId.trim());
        body.put("projectName", projectName == null ? "" : projectName.trim());
        body.put("name", NAME);
        try {
            storage.put(STORAGE_KEY, mapper.writeValueAsBytes(body), "application/json");
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL, "保存方舟素材组失败");
        }
    }
}
