package cn.aigc.dit.server.controller;

import cn.aigc.dit.common.security.LoginUser;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.server.media.ImageThumbnails;
import cn.aigc.dit.storage.LocalObjectStorage;
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
import java.nio.file.Path;

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
        var response = controller.get("users/7/drama/1/film.mp4", null, new HttpHeaders());
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("bytes", response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES));
        assertEquals(8L, response.getHeaders().getContentLength());
        assertEquals("video/mp4", response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, response.getBody().getInputStream().readAllBytes());
    }

    @Test
    void rangeRequestReturnsPartialContent() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RANGE, "bytes=2-5");
        var response = controller.get("users/7/drama/1/film.mp4", null, headers);
        assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
        assertEquals("bytes 2-5/8", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
        assertEquals(4L, response.getHeaders().getContentLength());
        assertArrayEquals(new byte[]{3, 4, 5, 6}, response.getBody().getInputStream().readAllBytes());
    }

    @Test
    void unsatisfiableRangeReturns416() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RANGE, "bytes=20-30");
        var response = controller.get("users/7/drama/1/film.mp4", null, headers);
        assertEquals(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, response.getStatusCode());
        assertEquals("bytes */8", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
    }

    @Test
    void imageWithWidthReturnsCachedJpegThumbnail() throws Exception {
        byte[] png = solidPng(1200, 800);
        String key = "users/7/assets/hero.png";
        storage.put(key, png, "image/png");

        var response = controller.get(key, 400, new HttpHeaders());
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("image/jpeg", response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
        byte[] body = response.getBody().getInputStream().readAllBytes();
        assertTrue(body.length > 0);
        assertTrue(body.length < png.length);

        String cacheKey = ImageThumbnails.cacheKey(key, 480);
        assertTrue(objectExists(cacheKey));
        assertArrayEquals(body, storage.getBytes(cacheKey));
    }

    @Test
    void missingImageThumbnailReturnsNotFoundWithoutReading() {
        var response = controller.get("users/7/outputs/287/1.png", 480, new HttpHeaders());
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
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
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
