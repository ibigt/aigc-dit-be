package cn.aigc.dit.server.controller;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.common.security.LoginUser;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.server.media.ImageThumbnails;
import cn.aigc.dit.storage.LocalObjectStorage;
import cn.aigc.dit.storage.ObjectStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaControllerTest {

    @TempDir
    Path temp;

    private LocalObjectStorage storage;
    private MediaController controller;

    @BeforeEach
    void setUp() {
        storage = new LocalObjectStorage(temp);
        controller = new MediaController(storage);
        UserContext.set(new LoginUser(7L, "creator", "c@example.com", "创作者", LoginUser.ROLE_CREATOR));
        storage.put("users/7/drama/1/film.mp4", new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, "video/mp4");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void fullResponseAdvertisesByteRanges() throws Exception {
        var response = controller.get("users/7/drama/1/film.mp4", null, null, new HttpHeaders());
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("bytes", response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES));
        assertEquals(8L, response.getHeaders().getContentLength());
        assertEquals("video/mp4", response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(disposition != null && disposition.contains("film.mp4"));
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, response.getBody().getInputStream().readAllBytes());
    }

    @Test
    void customFilenameIsAdvertisedForDownload() {
        var response = controller.get("users/7/drama/1/film.mp4", null, "短剧名-第1集.mp4", new HttpHeaders());
        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(disposition != null && disposition.contains("UTF-8''"));
        assertTrue(disposition.contains("%E7%9F%AD%E5%89%A7%E5%90%8D") || disposition.contains("短剧名"));
    }

    @Test
    void rangeRequestReturnsPartialContent() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RANGE, "bytes=2-5");
        var response = controller.get("users/7/drama/1/film.mp4", null, null, headers);
        assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
        assertEquals("bytes 2-5/8", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
        assertEquals(4L, response.getHeaders().getContentLength());
        assertArrayEquals(new byte[]{3, 4, 5, 6}, response.getBody().getInputStream().readAllBytes());
    }

    @Test
    void unsatisfiableRangeReturns416() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RANGE, "bytes=20-30");
        var response = controller.get("users/7/drama/1/film.mp4", null, null, headers);
        assertEquals(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, response.getStatusCode());
        assertEquals("bytes */8", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
    }

    @Test
    void imageWithWidthReturnsCachedJpegThumbnail() throws Exception {
        byte[] png = solidPng(1200, 800);
        String key = "users/7/assets/hero.png";
        storage.put(key, png, "image/png");

        var response = controller.get(key, 400, null, new HttpHeaders());
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("image/jpeg", response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
        byte[] body = response.getBody().getInputStream().readAllBytes();
        assertTrue(body.length > 0);
        assertTrue(body.length < png.length);

        String cacheKey = ImageThumbnails.cacheKey(key, 480, png.length);
        assertTrue(objectExists(cacheKey));
        assertArrayEquals(body, storage.getBytes(cacheKey));
    }

    @Test
    void overwrittenImageBustsStaleThumbnailCache() throws Exception {
        String key = "users/7/outputs/420/1.png";
        byte[] first = solidPng(800, 450, Color.RED);
        storage.put(key, first, "image/png");
        byte[] thumbFirst = controller.get(key, 480, null, new HttpHeaders()).getBody().getInputStream().readAllBytes();
        String staleLegacy = ImageThumbnails.cacheKey(key, 480);
        storage.put(staleLegacy, thumbFirst, "image/jpeg");

        // 不同尺寸 → 不同字节数，才能让带 size 的缓存键失效。
        byte[] second = solidPng(960, 540, Color.BLUE);
        storage.put(key, second, "image/png");
        assertTrue(second.length != first.length);

        byte[] thumbSecond = controller.get(key, 480, null, new HttpHeaders()).getBody().getInputStream().readAllBytes();
        String freshKey = ImageThumbnails.cacheKey(key, 480, second.length);
        assertTrue(objectExists(freshKey));
        assertTrue(!java.util.Arrays.equals(thumbFirst, thumbSecond));
        assertArrayEquals(thumbSecond, storage.getBytes(freshKey));
    }

    @Test
    void missingImageThumbnailReturnsNotFoundWithoutReading() {
        var response = controller.get("users/7/outputs/287/1.png", 480, null, new HttpHeaders());
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void storageTimeoutIsUnavailableAndNotCached() {
        MediaController blocked = new MediaController(unavailableStorage());
        var thumbnail = blocked.get("users/7/outputs/1.png", 480, null, new HttpHeaders());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, thumbnail.getStatusCode());
        assertEquals("no-store", thumbnail.getHeaders().getCacheControl());

        var original = blocked.get("users/7/outputs/1.png", null, null, new HttpHeaders());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, original.getStatusCode());
        assertEquals("no-store", original.getHeaders().getCacheControl());
    }

    private static ObjectStorage unavailableStorage() {
        return new ObjectStorage() {
            @Override
            public void put(String storageKey, InputStream content, long contentLength, String contentType) {
                throw unavailable();
            }

            @Override
            public InputStream get(String storageKey) {
                throw unavailable();
            }

            @Override
            public boolean exists(String storageKey) {
                throw unavailable();
            }

            @Override
            public long size(String storageKey) {
                throw unavailable();
            }

            @Override
            public void delete(String storageKey) {
                throw unavailable();
            }

            @Override
            public String presignGet(String storageKey, Duration expiry) {
                throw unavailable();
            }

            @Override
            public String presignPut(String storageKey, Duration expiry, String contentType) {
                throw unavailable();
            }

            private BizException unavailable() {
                return new BizException(ErrorCode.UNAVAILABLE, "对象存储暂不可用");
            }
        };
    }

    private boolean objectExists(String key) {
        try {
            storage.size(key);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] solidPng(int width, int height) throws Exception {
        return solidPng(width, height, Color.BLUE);
    }

    private static byte[] solidPng(int width, int height, Color color) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
