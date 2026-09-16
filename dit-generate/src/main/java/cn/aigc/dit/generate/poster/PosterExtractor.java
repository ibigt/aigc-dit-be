package cn.aigc.dit.generate.poster;

/**
 * 成片后抽封面。M2 用 ffmpeg 写 {@code poster.webp}；落幅写 {@code tail.webp}。
 */
public interface PosterExtractor {

    record VideoStills(String posterKey, String tailKey) {
    }

    /**
     * @param storageKey 原片相对 key
     * @return poster 相对 key
     */
    String extract(String storageKey);

    /**
     * @param storageKey 原片相对 key
     * @return 末帧相对 key；图片回退为首帧，视频抽帧失败时为空
     */
    default String extractTail(String storageKey) {
        return null;
    }

    default VideoStills extractStills(String storageKey) {
        return new VideoStills(extract(storageKey), extractTail(storageKey));
    }
}
