package cn.aigc.dit.server.media;

import cn.aigc.dit.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssetUploadPolicyTest {
    @Test
    void acceptsSupportedExtensionWhenBrowserMimeIsMissing() {
        AssetUploadPolicy.Validated value = AssetUploadPolicy.validate("voice.m4a", "application/octet-stream", 1024);
        assertEquals("audio", value.kind());
        assertEquals("audio/mp4", value.mimeType());
    }

    @Test
    void rejectsExtensionAndMimeMismatch() {
        assertThrows(BizException.class, () -> AssetUploadPolicy.validate("fake.png", "video/mp4", 1024));
    }

    @Test
    void enforcesPerKindSizeLimit() {
        assertThrows(BizException.class, () -> AssetUploadPolicy.validate("large.png", "image/png", 20L * 1024 * 1024 + 1));
        assertThrows(BizException.class, () -> AssetUploadPolicy.validate("large.mp4", "video/mp4", 200L * 1024 * 1024 + 1));
    }
}
