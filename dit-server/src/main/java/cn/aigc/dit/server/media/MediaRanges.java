package cn.aigc.dit.server.media;

import org.springframework.http.HttpRange;

import java.util.List;

/**
 * 解析视频预览所需的 HTTP Range。浏览器拖动进度条会先发 Range，再按 206 切片拉流。
 */
public final class MediaRanges {

    public record Slice(long start, long end, boolean partial) {
        public long length() {
            return end < start ? 0 : end - start + 1;
        }
    }

    private MediaRanges() {
    }

    public static Slice resolve(long contentLength, List<HttpRange> ranges) {
        if (contentLength <= 0) {
            return new Slice(0, -1, false);
        }
        if (ranges == null || ranges.isEmpty()) {
            return new Slice(0, contentLength - 1, false);
        }
        HttpRange range = ranges.get(0);
        long start = range.getRangeStart(contentLength);
        long end = range.getRangeEnd(contentLength);
        if (start < 0 || start >= contentLength || start > end) {
            throw new IllegalArgumentException("unsatisfiable range");
        }
        return new Slice(start, end, true);
    }
}
