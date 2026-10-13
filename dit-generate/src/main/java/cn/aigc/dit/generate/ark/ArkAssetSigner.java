package cn.aigc.dit.generate.ark;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 火山 OpenAPI HMAC-SHA256。素材库管控面用 AK/SK，不用视频任务的 Bearer API Key。
 */
public final class ArkAssetSigner {

    static final DateTimeFormatter X_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    private ArkAssetSigner() {
    }

    public record Signed(String authorization, String xDate, String contentSha256, Map<String, String> headers) {
    }

    public static Signed sign(String accessKey, String secretKey, String region, String service,
                               URI uri, byte[] body, Instant now) {
        byte[] payload = body == null ? new byte[0] : body;
        String xDate = X_DATE.format(now);
        String shortDate = xDate.substring(0, 8);
        String contentSha256 = sha256Hex(payload);
        String host = hostHeader(uri);
        Map<String, String> headers = new TreeMap<>();
        headers.put("content-type", "application/json");
        headers.put("host", host);
        headers.put("x-content-sha256", contentSha256);
        headers.put("x-date", xDate);

        String canonicalHeaders = canonicalHeaders(headers);
        String signedHeaders = String.join(";", headers.keySet());
        String canonical = "POST\n"
                + canonicalUri(uri) + "\n"
                + canonicalQuery(uri) + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + contentSha256;
        String scope = shortDate + "/" + region + "/" + service + "/request";
        String stringToSign = "HMAC-SHA256\n" + xDate + "\n" + scope + "\n" + sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
        byte[] signingKey = signingKey(secretKey, shortDate, region, service);
        String signature = hex(hmac(signingKey, stringToSign.getBytes(StandardCharsets.UTF_8)));
        String authorization = "HMAC-SHA256 Credential=" + accessKey + "/" + scope
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;
        return new Signed(authorization, xDate, contentSha256, headers);
    }

    static String hostHeader(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost();
        int port = uri.getPort();
        if (port > 0 && port != 80 && port != 443) {
            return host + ":" + port;
        }
        return host;
    }

    private static String canonicalUri(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private static String canonicalQuery(URI uri) {
        String raw = uri.getRawQuery();
        if (raw == null || raw.isBlank()) {
            return "";
        }
        TreeMap<String, String> ordered = new TreeMap<>();
        for (String part : raw.split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            int eq = part.indexOf('=');
            String name = eq >= 0 ? part.substring(0, eq) : part;
            String value = eq >= 0 ? part.substring(eq + 1) : "";
            ordered.put(uriEncode(decode(name)), uriEncode(decode(value)));
        }
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : ordered.entrySet()) {
            if (!query.isEmpty()) {
                query.append('&');
            }
            query.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return query.toString();
    }

    private static String decode(String value) {
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String canonicalHeaders(Map<String, String> headers) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            builder.append(entry.getKey().toLowerCase(Locale.ROOT))
                    .append(':')
                    .append(entry.getValue().trim())
                    .append('\n');
        }
        return builder.toString();
    }

    static String uriEncode(String value) {
        StringBuilder encoded = new StringBuilder();
        for (byte current : value.getBytes(StandardCharsets.UTF_8)) {
            int unsigned = current & 0xff;
            if (unreserved(unsigned)) {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%');
                encoded.append(Character.toUpperCase(Character.forDigit(unsigned >>> 4, 16)));
                encoded.append(Character.toUpperCase(Character.forDigit(unsigned & 0x0f, 16)));
            }
        }
        return encoded.toString();
    }

    private static boolean unreserved(int value) {
        return (value >= 'A' && value <= 'Z')
                || (value >= 'a' && value <= 'z')
                || (value >= '0' && value <= '9')
                || value == '-' || value == '_' || value == '.' || value == '~';
    }

    private static byte[] signingKey(String secretKey, String shortDate, String region, String service) {
        byte[] date = hmac(secretKey.getBytes(StandardCharsets.UTF_8), shortDate.getBytes(StandardCharsets.UTF_8));
        byte[] regionKey = hmac(date, region.getBytes(StandardCharsets.UTF_8));
        byte[] serviceKey = hmac(regionKey, service.getBytes(StandardCharsets.UTF_8));
        return hmac(serviceKey, "request".getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String hex(byte[] data) {
        return java.util.HexFormat.of().formatHex(data);
    }
}
