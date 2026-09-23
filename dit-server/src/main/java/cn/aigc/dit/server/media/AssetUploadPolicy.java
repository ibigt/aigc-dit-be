package cn.aigc.dit.server.media;

import cn.aigc.dit.common.constant.AssetKind;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class AssetUploadPolicy {
    private static final long MB = 1024L * 1024L;
    private static final Map<String, Rule> RULES = Map.of(
            AssetKind.IMAGE, new Rule(Set.of("jpg", "jpeg", "png", "webp", "gif"), 20 * MB),
            AssetKind.VIDEO, new Rule(Set.of("mp4", "webm", "mov"), 200 * MB),
            AssetKind.AUDIO, new Rule(Set.of("mp3", "wav", "m4a", "aac", "flac", "ogg"), 100 * MB));

    private static final Map<String, String> MIME_BY_EXTENSION = Map.ofEntries(
            Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
            Map.entry("png", "image/png"), Map.entry("webp", "image/webp"), Map.entry("gif", "image/gif"),
            Map.entry("mp4", "video/mp4"), Map.entry("webm", "video/webm"), Map.entry("mov", "video/quicktime"),
            Map.entry("mp3", "audio/mpeg"), Map.entry("wav", "audio/wav"), Map.entry("m4a", "audio/mp4"),
            Map.entry("aac", "audio/aac"), Map.entry("flac", "audio/flac"), Map.entry("ogg", "audio/ogg"));

    private AssetUploadPolicy() {
    }

    public static Validated validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择非空文件");
        }
        return validate(file.getOriginalFilename(), file.getContentType(), file.getSize());
    }

    static Validated validate(String filename, String contentType, long size) {
        String extension = extensionOf(filename);
        String extensionKind = kindForExtension(extension);
        if (extensionKind == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持的文件格式");
        }
        String mime = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
        String mimeKind = mime.startsWith("image/") ? AssetKind.IMAGE
                : mime.startsWith("video/") ? AssetKind.VIDEO
                : mime.startsWith("audio/") ? AssetKind.AUDIO : null;
        if (mimeKind != null && !mimeKind.equals(extensionKind)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件扩展名与媒体类型不一致");
        }
        Rule rule = RULES.get(extensionKind);
        if (size <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件为空");
        }
        if (size > rule.maxBytes()) {
            throw new BizException(ErrorCode.BAD_REQUEST, switch (extensionKind) {
                case AssetKind.IMAGE -> "图片不能超过20MB";
                case AssetKind.VIDEO -> "视频不能超过200MB";
                default -> "音频不能超过100MB";
            });
        }
        return new Validated(extensionKind, extension, mimeKind == null ? MIME_BY_EXTENSION.get(extension) : mime);
    }

    private static String kindForExtension(String extension) {
        for (Map.Entry<String, Rule> entry : RULES.entrySet()) {
            if (entry.getValue().extensions().contains(extension)) return entry.getKey();
        }
        return null;
    }

    private static String extensionOf(String filename) {
        if (!StringUtils.hasText(filename)) return "";
        String value = filename.trim().replace('\\', '/');
        int slash = value.lastIndexOf('/');
        String base = slash >= 0 ? value.substring(slash + 1) : value;
        int dot = base.lastIndexOf('.');
        return dot < 0 ? "" : base.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private record Rule(Set<String> extensions, long maxBytes) {
    }

    public record Validated(String kind, String extension, String mimeType) {
        public String dottedExtension() {
            return "." + extension;
        }
    }
}
