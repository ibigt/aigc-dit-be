package cn.aigc.dit.generate.ark;

import cn.aigc.dit.generate.AdapterCatalog;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 火山方舟 Seedance 官方地址：创建/查询都在 {@code /contents/generations/tasks}。
 * 接入来源「官方API」走 {@code /api/v3}，「官方API（token plan）」走 {@code /api/plan/v3}。
 */
public final class ArkUrls {

    public static final String DEFAULT_BASE = "https://ark.cn-beijing.volces.com/api/v3";
    public static final String PLAN_BASE = "https://ark.cn-beijing.volces.com/api/plan/v3";
    public static final String TASKS_PATH = "/contents/generations/tasks";

    private static final String[] API_PREFIXES = {
            "/api/plan/v3",
            "/api/coding/v3",
            "/api/v3"
    };

    private ArkUrls() {
    }

    public static String v3Base(String endpoint, String fallback) {
        return v3Base(endpoint, fallback, null);
    }

    public static String v3Base(String endpoint, String fallback, String provider) {
        String raw = stripTrailingSlash(blankTo(endpoint, blankTo(fallback, DEFAULT_BASE)));
        String matched = matchKnownPrefix(raw);
        String base = matched != null ? matched : raw + "/api/v3";
        if (AdapterCatalog.isOfficialTokenPlan(provider) && isPaygV3(base)) {
            int marker = indexOfIgnoreCase(base, "/api/v3");
            return base.substring(0, marker) + "/api/plan/v3";
        }
        return base;
    }

    public static String createTask(String v3Base) {
        return stripTrailingSlash(v3Base) + TASKS_PATH;
    }

    public static String queryTask(String v3Base, String taskId) {
        return createTask(v3Base) + "/" + encode(taskId);
    }

    /**
     * token plan 创建在 {@code /api/plan/v3}，部分账号查询只认 {@code /api/v3}。
     * 官方按量不回退到 plan。
     */
    public static List<String> queryUrls(String v3Base, String taskId) {
        List<String> urls = new ArrayList<>();
        urls.add(queryTask(v3Base, taskId));
        String other = otherV3Base(v3Base);
        if (other != null) {
            String alt = queryTask(other, taskId);
            if (!alt.equalsIgnoreCase(urls.get(0))) {
                urls.add(alt);
            }
        }
        return urls;
    }

    public static String baseOfQueryUrl(String queryUrl) {
        String raw = stripTrailingSlash(blankTo(queryUrl, ""));
        int marker = indexOfIgnoreCase(raw, TASKS_PATH);
        if (marker > 0) {
            return raw.substring(0, marker);
        }
        return raw;
    }

    static String otherV3Base(String v3Base) {
        String raw = stripTrailingSlash(blankTo(v3Base, DEFAULT_BASE));
        int plan = indexOfIgnoreCase(raw, "/api/plan/v3");
        if (plan >= 0) {
            return raw.substring(0, plan) + "/api/v3";
        }
        return null;
    }

    public static URI toUri(String url) {
        return URI.create(url);
    }

    private static boolean isPaygV3(String base) {
        return indexOfIgnoreCase(base, "/api/plan/v3") < 0
                && indexOfIgnoreCase(base, "/api/coding/v3") < 0
                && indexOfIgnoreCase(base, "/api/v3") >= 0;
    }

    private static String matchKnownPrefix(String raw) {
        for (String prefix : API_PREFIXES) {
            int marker = indexOfIgnoreCase(raw, prefix);
            if (marker >= 0) {
                return raw.substring(0, marker + prefix.length());
            }
        }
        return null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(blankTo(value, ""), StandardCharsets.UTF_8);
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String stripTrailingSlash(String value) {
        String text = value;
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private static int indexOfIgnoreCase(String haystack, String needle) {
        return haystack.toLowerCase().indexOf(needle.toLowerCase());
    }
}
