package cn.aigc.dit.server.controller;

import cn.aigc.dit.common.security.LoginUser;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.storage.LocalObjectStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
        var response = controller.get("users/7/drama/1/film.mp4", new HttpHeaders());
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
        var response = controller.get("users/7/drama/1/film.mp4", headers);
        assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
        assertEquals("bytes 2-5/8", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
        assertEquals(4L, response.getHeaders().getContentLength());
        assertArrayEquals(new byte[]{3, 4, 5, 6}, response.getBody().getInputStream().readAllBytes());
    }

    @Test
    void unsatisfiableRangeReturns416() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RANGE, "bytes=20-30");
        var response = controller.get("users/7/drama/1/film.mp4", headers);
        assertEquals(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, response.getStatusCode());
        assertEquals("bytes */8", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
    }
}
