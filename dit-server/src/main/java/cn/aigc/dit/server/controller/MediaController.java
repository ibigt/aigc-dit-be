package cn.aigc.dit.server.controller;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.server.media.MediaRanges;
import cn.aigc.dit.server.media.MediaUrls;
import cn.aigc.dit.storage.ObjectStorage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
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

import java.io.InputStream;
import java.time.Duration;
import java.util.List;

@Tag(name = "媒体")
@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
public class MediaController {

    private final ObjectStorage objectStorage;

    @Operation(summary = "读取对象存储文件（不直连 MinIO）；支持 Range，供视频进度条拖动")
    @RequestMapping(method = {RequestMethod.GET, RequestMethod.HEAD})
    public ResponseEntity<InputStreamResource> get(
            @RequestParam String key,
            @RequestHeader HttpHeaders headers) {
        if (!StringUtils.hasText(key) || key.contains("..")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "非法对象路径");
        }
        var user = UserContext.require();
        if (!user.isAdmin() && !key.startsWith("users/" + user.getUserId() + "/")) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权访问该对象");
        }
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
