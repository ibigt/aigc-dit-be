package cn.aigc.dit.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppVersionTest {

    @Test
    void readsTrimmedVersion() {
        ByteArrayResource resource = new ByteArrayResource("V1.0\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("V1.0", AppVersion.read(resource));
    }

    @Test
    void blankContentIsEmpty() {
        ByteArrayResource resource = new ByteArrayResource(" \n".getBytes(StandardCharsets.UTF_8));
        assertEquals("", AppVersion.read(resource));
    }
}
