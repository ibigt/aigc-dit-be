package cn.aigc.dit.generate.comfyui;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * ComfyUI 根地址与常用路径。
 */
public final class ComfyUiUrls {

    private ComfyUiUrls() {
    }

    public static String base(String endpoint, String fallback) {
        String raw = blankTo(endpoint, fallback);
        return stripTrailingSlash(raw.trim());
    }

    public static URI prompt(String base) {
        return URI.create(base + "/prompt");
    }

    public static URI history(String base, String promptId) {
        return URI.create(base + "/history/" + encodePath(promptId));
    }

    public static URI interrupt(String base) {
        return URI.create(base + "/interrupt");
    }

    public static URI uploadImage(String base) {
        return URI.create(base + "/upload/image");
    }

    public static URI view(String base, String filename, String subfolder, String type) {
        String qs = "filename=" + encode(filename)
                + "&subfolder=" + encode(subfolder == null ? "" : subfolder)
                + "&type=" + encode(type == null || type.isBlank() ? "output" : type);
        return URI.create(base + "/view?" + qs);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String encodePath(String value) {
        return encode(value).replace("+", "%20");
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String stripTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
