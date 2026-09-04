package cn.aigc.dit.server.controller;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.server.media.MediaUrls;
import cn.aigc.dit.storage.ObjectStorage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.time.Duration;

@Tag(name = "媒体")
@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
public class MediaController {

    private final ObjectStorage objectStorage;

    @Operation(summary = "读取对象存储文件（不直连 MinIO）")
    @GetMapping
    public ResponseEntity<InputStreamResource> get(@RequestParam String key) {
        if (!StringUtils.hasText(key) || key.contains("..")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "非法对象路径");
        }
        var user = UserContext.require();
        if (!user.isAdmin() && !key.startsWith("users/" + user.getUserId() + "/")) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权访问该对象");
        }
        InputStream stream = objectStorage.get(key);
        String contentType = MediaUrls.contentType(key, MediaType.APPLICATION_OCTET_STREAM_VALUE);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(new InputStreamResource(stream));
    }
}
