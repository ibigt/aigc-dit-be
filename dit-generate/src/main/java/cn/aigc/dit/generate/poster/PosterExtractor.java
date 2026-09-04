package cn.aigc.dit.generate.poster;

/**
 * 成片后抽封面。M2 用 ffmpeg 写 {@code poster.webp}。
 */
public interface PosterExtractor {

    /**
     * @param storageKey 原片相对 key
     * @return poster 相对 key
     */
    String extract(String storageKey);
}
