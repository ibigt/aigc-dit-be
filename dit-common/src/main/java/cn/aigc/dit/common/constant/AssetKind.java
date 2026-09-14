package cn.aigc.dit.common.constant;

/**
 * 素材种类。
 */
public final class AssetKind {
    public static final String IMAGE = "image";
    public static final String VIDEO = "video";
    public static final String AUDIO = "audio";

    private AssetKind() {
    }

    public static String fromMime(String mimeType) {
        if (mimeType == null) {
            return IMAGE;
        }
        if (mimeType.startsWith("video/")) {
            return VIDEO;
        }
        if (mimeType.startsWith("audio/")) {
            return AUDIO;
        }
        return IMAGE;
    }
}
