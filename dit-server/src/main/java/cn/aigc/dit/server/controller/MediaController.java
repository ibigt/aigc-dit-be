package cn.aigc.dit.server.controller;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.server.media.ImageThumbnails;
import cn.aigc.dit.server.media.MediaRanges;
import cn.aigc.dit.server.media.MediaUrls;
import cn.aigc.dit.storage.ObjectStorage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Tag(name = "媒体")
@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
@Slf4j
public class MediaController {

    private final ObjectStorage objectStorage;
    /** 超过这个大小就不再为列表预览整图解码，避免一张大图占满页面连接。 */
    private static final long MAX_THUMB_SOURCE_BYTES = 12L * 1024 * 1024;
    /** 同一张缩略图只生成一次。没抢到锁的请求立刻返回，不占着浏览器连接。 */
    private final ConcurrentHashMap<String, ReentrantLock> thumbnailLocks = new ConcurrentHashMap<>();

    @Operation(summary = "读取对象存储文件（不直连 MinIO）；支持 Range；图片可传 w 返回缩略图")
    @RequestMapping(method = {RequestMethod.GET, RequestMethod.HEAD})
    public ResponseEntity<InputStreamResource> get(
            @RequestParam String key,
            @RequestParam(required = false) Integer w,
            @RequestHeader HttpHeaders headers) {
        if (!StringUtils.hasText(key) || key.contains("..")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "非法对象路径");
        }
        var user = UserContext.require();
        if (!user.isAdmin() && !key.startsWith("users/" + user.getUserId() + "/")) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权访问该对象");
        }
        Integer thumbW = ImageThumbnails.normalizeWidth(w);
        if (thumbW != null && ImageThumbnails.supportsThumbnail(key)) {
            ResponseEntity<InputStreamResource> thumb = serveThumbnail(key, thumbW);
            if (thumb != null) {
                return thumb;
            }
        }
        return serveOriginal(key, headers);
    }

    private ResponseEntity<InputStreamResource> serveThumbnail(String key, int width) {
        String cacheKey = ImageThumbnails.cacheKey(key, width);
        ResponseEntity<InputStreamResource> cached = readCachedThumbnail(cacheKey);
        if (cached != null) {
            return cached;
        }
        ReentrantLock lock = thumbnailLocks.computeIfAbsent(cacheKey, ignored -> new ReentrantLock());
        boolean locked;
        try {
            locked = lock.tryLock(200, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .cacheControl(CacheControl.noStore())
                    .build();
        }
        if (!locked) {
            cached = readCachedThumbnail(cacheKey);
            return cached != null
                    ? cached
                    : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .cacheControl(CacheControl.noStore())
                            .build();
        }
        try {
            cached = readCachedThumbnail(cacheKey);
            if (cached != null) {
                return cached;
            }
            if (!objectStorage.exists(key)) {
                return ResponseEntity.notFound().build();
            }
            long originalSize = objectStorage.size(key);
            if (originalSize <= 0 || originalSize > MAX_THUMB_SOURCE_BYTES) {
                log.warn("skip inline thumbnail, key={} bytes={}", key, originalSize);
                return ResponseEntity.notFound().build();
            }
            byte[] original = objectStorage.getBytes(key);
            ImageThumbnails.Thumb thumb = ImageThumbnails.resizeToJpeg(original, width);
            if (thumb == null) {
                return null;
            }
            try {
                objectStorage.put(cacheKey, thumb.bytes(), thumb.contentType());
            } catch (Exception e) {
                log.debug("thumbnail cache put failed, key={}", cacheKey, e);
            }
            return okBytes(new ByteArrayInputStream(thumb.bytes()), thumb.bytes().length, thumb.contentType());
        } finally {
            lock.unlock();
            if (!lock.hasQueuedThreads()) {
                thumbnailLocks.remove(cacheKey, lock);
            }
        }
    }

    /** 缓存未命中是正常情况，用 exists 探测，避免对缺失对象打出 S3 异常栈。 */
    private ResponseEntity<InputStreamResource> readCachedThumbnail(String cacheKey) {
        if (!objectStorage.exists(cacheKey)) {
            return null;
        }
        long length = objectStorage.size(cacheKey);
        if (length <= 0) {
            return null;
        }
        return okBytes(objectStorage.get(cacheKey), length, "image/jpeg");
    }

    private ResponseEntity<InputStreamResource> serveOriginal(String key, HttpHeaders headers) {
        long contentLength = objectStorage.size(key);
        List<HttpRange> ranges;
        try {
            ranges = headers == null ? List.of() : headers.getRange();
        } catch (IllegalArgumentException e) {
            return unsatisfiable(contentLength);
        }
        MediaRanges.Slice slice;
        try {
            slice = MediaRanges.resolve(contentLength, ranges);
        } catch (IllegalArgumentException e) {
            return unsatisfiable(contentLength);
        }
        String contentType = MediaUrls.contentType(key, MediaType.APPLICATION_OCTET_STREAM_VALUE);
        if (contentLength <= 0) {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                    .contentLength(0)
                    .body(new SizedStreamResource(InputStream.nullInputStream(), 0));
        }
        InputStream stream = objectStorage.getRange(key, slice.start(), slice.end());
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.set(HttpHeaders.CONTENT_DISPOSITION, "inline");
        responseHeaders.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        responseHeaders.setCacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate());
        responseHeaders.setContentType(MediaType.parseMediaType(contentType));
        responseHeaders.setContentLength(slice.length());
        if (slice.partial()) {
            responseHeaders.set(HttpHeaders.CONTENT_RANGE,
                    "bytes " + slice.start() + "-" + slice.end() + "/" + contentLength);
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .headers(responseHeaders)
                    .body(new SizedStreamResource(stream, slice.length()));
        }
        return ResponseEntity.ok()
                .headers(responseHeaders)
                .body(new SizedStreamResource(stream, slice.length()));
    }

    private static ResponseEntity<InputStreamResource> okBytes(InputStream stream, long length, String contentType) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate())
                .contentLength(length)
                .body(new SizedStreamResource(stream, length));
    }

    private static ResponseEntity<InputStreamResource> unsatisfiable(long contentLength) {
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_RANGE, "bytes */" + Math.max(contentLength, 0))
                .build();
    }

    static final class SizedStreamResource extends InputStreamResource {
        private final long length;

        SizedStreamResource(InputStream stream, long length) {
            super(stream);
            this.length = length;
        }

        @Override
        public long contentLength() {
            return length;
        }
    }
}
