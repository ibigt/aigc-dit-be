package cn.aigc.dit.generate.seedream;

import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.generate.GenerateMode;
import cn.aigc.dit.generate.GenerateRequest;
import cn.aigc.dit.storage.ObjectStorage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * Seedream 5.0 lite / pro / flash 文生图的方舟原始地址。
 * Seedance 信任同一账号、未改动的 5.0 文生图原件；本地转存后再 Base64 会失去这份信任。
 * 方舟返回的图片地址大约 24 小时失效，过期后调用方应回退到本地文件。
 */
public final class SeedreamOriginUrls {

    static final Duration URL_TTL = Duration.ofHours(24);
    private static final String SUFFIX = ".origin.json";

    private SeedreamOriginUrls() {
    }

    public static boolean qualifies(GenerateRequest request) {
        if (request == null) {
            return false;
        }
        String mode = GenerateMode.canonical(request.getMode());
        if (!GenerateMode.T2I.equalsIgnoreCase(mode)) {
            return false;
        }
        return isSeedream50(request.getAdapterKey(), request.getModelKey());
    }

    /**
     * 5.0 lite / pro / flash 文生图都记原始地址。4.x 不记。
     * 适配器是 seedream-5.0 且未填模型标识时，实际请求用的是默认 5.0 pro。
     */
    public static boolean isSeedream50(String adapterKey, String modelKey) {
        String adapter = adapterKey == null ? "" : adapterKey.trim();
        String model = modelKey == null ? "" : modelKey.trim().toLowerCase(Locale.ROOT);
        if (model.isEmpty()) {
            return AdapterCatalog.SEEDREAM_5_0.equalsIgnoreCase(adapter);
        }
        if (model.contains("seedream-4") || model.contains("seedream_4") || model.contains("doubao-seedream-4")) {
            return false;
        }
        boolean seedream50 = model.contains("seedream-5")
                || model.contains("seedream_5")
                || model.contains("doubao-seedream-5");
        if (!seedream50) {
            return false;
        }
        return adapter.isEmpty() || AdapterCatalog.SEEDREAM_5_0.equalsIgnoreCase(adapter);
    }

    public static String sidecarKey(String storageKey) {
        return storageKey + SUFFIX;
    }

    public static void save(ObjectStorage storage, ObjectMapper mapper, String storageKey, String url, Instant savedAt) {
        if (storage == null || !isHttps(url) || storageKey == null || storageKey.isBlank() || savedAt == null) {
            return;
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("url", url.trim());
        body.put("savedAt", savedAt.truncatedTo(ChronoUnit.MILLIS).toString());
        byte[] bytes;
        try {
            bytes = mapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            return;
        }
        storage.put(sidecarKey(storageKey), bytes, "application/json");
    }

    /**
     * @return 仍在有效期内的原始 HTTPS 地址；没有记录、已过期或无法读取时返回 null
     */
    public static String resolve(ObjectStorage storage, ObjectMapper mapper, String storageKey, Instant now) {
        if (storage == null || storageKey == null || storageKey.isBlank() || now == null) {
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
            String url = node.path("url").asText("").trim();
            if (!isHttps(url)) {
                return null;
            }
            Instant savedAt = parseInstant(node.path("savedAt").asText(""));
            if (savedAt == null) {
                return null;
            }
            Instant deadline = expiryOf(url, savedAt);
            return now.isBefore(deadline) ? url : null;
        } catch (RuntimeException | java.io.IOException e) {
            return null;
        }
    }

    static boolean isHttps(String url) {
        if (url == null) {
            return false;
        }
        String value = url.trim().toLowerCase(Locale.ROOT);
        return value.startsWith("https://") && value.length() > "https://".length();
    }

    static Instant expiryOf(String url, Instant savedAt) {
        Instant ttl = savedAt.plus(URL_TTL);
        Instant signed = signedExpiry(url);
        if (signed == null || signed.isAfter(ttl)) {
            return ttl;
        }
        return signed;
    }

    private static Instant signedExpiry(String url) {
        try {
            URI uri = URI.create(url.trim());
            String query = uri.getRawQuery();
            if (query == null || query.isBlank()) {
                return null;
            }
            String date = null;
            String expires = null;
            for (String part : query.split("&")) {
                int eq = part.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String name = part.substring(0, eq);
                String value = part.substring(eq + 1);
                if ("X-Tos-Date".equalsIgnoreCase(name)) {
                    date = value;
                } else if ("X-Tos-Expires".equalsIgnoreCase(name)) {
                    expires = value;
                }
            }
            if (date == null || expires == null) {
                return null;
            }
            Instant issued = parseTosDate(date);
            long seconds = Long.parseLong(expires);
            if (issued == null || seconds <= 0) {
                return null;
            }
            return issued.plusSeconds(seconds);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Instant parseTosDate(String value) {
        if (value == null || value.length() < 16) {
            return null;
        }
        try {
            String iso = value.substring(0, 4) + "-"
                    + value.substring(4, 6) + "-"
                    + value.substring(6, 8) + "T"
                    + value.substring(9, 11) + ":"
                    + value.substring(11, 13) + ":"
                    + value.substring(13, 15) + "Z";
            return Instant.parse(iso);
        } catch (RuntimeException e) {
            return null;
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
