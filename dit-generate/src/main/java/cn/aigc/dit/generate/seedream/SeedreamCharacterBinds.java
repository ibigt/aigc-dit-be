package cn.aigc.dit.generate.seedream;

import cn.aigc.dit.storage.ObjectStorage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * 原图旁边的 Seedream 定妆绑定。不新建资产，也不改角色页选用的 {@code imageStorageKey}。
 * <ul>
 *   <li>{@code {原图key}.seedream.png}：定妆成图</li>
 *   <li>{@code {原图key}.seedream.png.origin.json}：方舟原始地址，格式与 {@link SeedreamOriginUrls} 相同</li>
 *   <li>{@code {原图key}.seedream-bind.json}：指向成图，并记下绑定时间</li>
 * </ul>
 * 方舟原链大约 24 小时失效；绑定本身未满 30 天就继续用已保存的成图。
 */
public final class SeedreamCharacterBinds {

    public static final Duration BIND_TTL = Duration.ofDays(30);
    public static final String IMAGE_SUFFIX = ".seedream.png";
    public static final String BIND_SUFFIX = ".seedream-bind.json";

    private SeedreamCharacterBinds() {
    }

    public static String imageKey(String originalKey) {
        return originalKey + IMAGE_SUFFIX;
    }

    public static String bindKey(String originalKey) {
        return originalKey + BIND_SUFFIX;
    }

    /** 成图自己的方舟原链，供 {@link SeedreamOriginUrls#resolve} 读取。 */
    public static String originKey(String originalKey) {
        return SeedreamOriginUrls.sidecarKey(imageKey(originalKey));
    }

    public record Bind(String imageKey, Instant boundAt) {
    }

    public static boolean fresh(Bind bind, Instant now) {
        return bind != null
                && bind.boundAt() != null
                && now != null
                && now.isBefore(bind.boundAt().plus(BIND_TTL));
    }

    public static Bind read(ObjectStorage storage, ObjectMapper mapper, String originalKey) {
        if (storage == null || mapper == null || originalKey == null || originalKey.isBlank()) {
            return null;
        }
        String key = bindKey(originalKey);
        if (!storage.exists(key)) {
            return null;
        }
        try {
            byte[] bytes = storage.getBytes(key);
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            JsonNode node = mapper.readTree(new String(bytes, StandardCharsets.UTF_8));
            String image = node.path("imageKey").asText("").trim();
            if (image.isEmpty()) {
                image = imageKey(originalKey);
            }
            Instant boundAt = parseInstant(node.path("boundAt").asText(""));
            if (boundAt == null) {
                return null;
            }
            return new Bind(image, boundAt);
        } catch (RuntimeException | java.io.IOException e) {
            return null;
        }
    }

    public static void save(ObjectStorage storage, ObjectMapper mapper, String originalKey,
                            String imageKey, Instant boundAt) {
        if (storage == null || mapper == null || boundAt == null
                || originalKey == null || originalKey.isBlank()
                || imageKey == null || imageKey.isBlank()) {
            return;
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("imageKey", imageKey.trim());
        body.put("boundAt", boundAt.truncatedTo(ChronoUnit.MILLIS).toString());
        byte[] bytes;
        try {
            bytes = mapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            return;
        }
        storage.put(bindKey(originalKey), bytes, "application/json");
    }

    /** 一次删掉成图、原链和绑定。原图本身不动。 */
    public static void deleteAll(ObjectStorage storage, String originalKey) {
        if (storage == null || originalKey == null || originalKey.isBlank()) {
            return;
        }
        deleteQuiet(storage, imageKey(originalKey));
        deleteQuiet(storage, originKey(originalKey));
        deleteQuiet(storage, bindKey(originalKey));
    }

    private static void deleteQuiet(ObjectStorage storage, String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException ignored) {
            // 过期绑定或原图已不在时，缺文件不算失败。
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
