package cn.aigc.dit.generate.runninghub;

/**
 * RunningHub OpenAPI v2 地址拼装。
 * 提交：{@code POST {base}/openapi/v2/{path}}；查询：{@code POST {base}/openapi/v2/query}。
 * path 来自 adapter_config 的 t2iPath / t2vPath / r2vPath。
 */
public final class RunningHubUrls {

    public static final String OPENAPI_V2 = "/openapi/v2";

    private RunningHubUrls() {
    }

    public static String openApiBase(String endpoint, String fallbackBase) {
        String raw = blankTo(endpoint, fallbackBase);
        raw = stripTrailingSlash(raw.trim());
        int marker = indexOfIgnoreCase(raw, OPENAPI_V2);
        if (marker >= 0) {
            return raw.substring(0, marker + OPENAPI_V2.length());
        }
        if (raw.toLowerCase().endsWith("/api")) {
            return raw.substring(0, raw.length() - 4) + OPENAPI_V2;
        }
        return raw + OPENAPI_V2;
    }

    public static String submitUrl(String openApiBase, String path) {
        String relative = stripLeadingSlash(blankTo(path, "rhart-image-g-2/text-to-image"));
        return stripTrailingSlash(openApiBase) + "/" + relative;
    }

    public static String queryUrl(String openApiBase) {
        return stripTrailingSlash(openApiBase) + "/query";
    }

    public static String uploadUrl(String openApiBase) {
        return stripTrailingSlash(openApiBase) + "/media/upload/binary";
    }

    public static boolean officialChannel(String t2iPath) {
        String path = t2iPath == null ? "" : t2iPath.toLowerCase();
        return path.contains("official");
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String stripTrailingSlash(String value) {
        String text = value;
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private static String stripLeadingSlash(String value) {
        String text = value;
        while (text.startsWith("/")) {
            text = text.substring(1);
        }
        return text;
    }

    private static int indexOfIgnoreCase(String haystack, String needle) {
        return haystack.toLowerCase().indexOf(needle.toLowerCase());
    }
}
