package cn.aigc.dit.common.constant;

/**
 * 素材种类。一期仅图片与视频。
 */
public final class AssetKind {
    public static final String IMAGE = "image";
    public static final String VIDEO = "video";

    private AssetKind() {
    }

    public static String fromMime(String mimeType) {
        if (mimeType == null) {
            return IMAGE;
        }
        if (mimeType.startsWith("video/")) {
            return VIDEO;
        }
        return IMAGE;
    }
}
