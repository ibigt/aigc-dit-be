package cn.aigc.dit.server.media;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class MediaUrls {

    private MediaUrls() {
    }

    public static String of(String storageKey) {
        return of(storageKey, null);
    }

    public static String of(String storageKey, Integer width) {
        if (storageKey == null || storageKey.isBlank()) {
            return null;
        }
        String url = "/api/media?key=" + URLEncoder.encode(storageKey, StandardCharsets.UTF_8);
        if (width != null && width > 0) {
            url += "&w=" + width;
        }
        return url;
    }

    public static String contentType(String storageKey, String fallback) {
        if (storageKey == null) {
            return fallback;
        }
        String lower = storageKey.toLowerCase();
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (lower.endsWith(".webm")) {
            return "video/webm";
        }
        if (lower.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (lower.endsWith(".wav")) {
            return "audio/wav";
        }
        if (lower.endsWith(".m4a")) {
            return "audio/mp4";
        }
        if (lower.endsWith(".aac")) {
            return "audio/aac";
        }
        if (lower.endsWith(".flac")) {
            return "audio/flac";
        }
        if (lower.endsWith(".opus")) {
            return "audio/opus";
        }
        if (lower.endsWith(".pcm")) {
            return "audio/L16";
        }
        return fallback == null ? "application/octet-stream" : fallback;
    }
}
