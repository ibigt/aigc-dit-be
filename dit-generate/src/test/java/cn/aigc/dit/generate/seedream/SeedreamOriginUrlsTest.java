package cn.aigc.dit.generate.seedream;

import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.generate.GenerateMode;
import cn.aigc.dit.generate.GenerateRequest;
import cn.aigc.dit.storage.LocalObjectStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeedreamOriginUrlsTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void onlyLiteTextToImageQualifies() {
        assertTrue(SeedreamOriginUrls.qualifies(request(GenerateMode.T2I, AdapterCatalog.SEEDREAM_5_0, "doubao-seedream-5.0-lite")));
        assertTrue(SeedreamOriginUrls.qualifies(request(GenerateMode.T2I, AdapterCatalog.SEEDREAM_5_0, "doubao-seedream-5.0-pro")));
        assertTrue(SeedreamOriginUrls.qualifies(request(GenerateMode.T2I, AdapterCatalog.SEEDREAM_5_0, "Doubao-Seedream-5-0-flash")));
        assertTrue(SeedreamOriginUrls.qualifies(request(GenerateMode.T2I, AdapterCatalog.SEEDREAM_5_0, null)));
        assertTrue(SeedreamOriginUrls.qualifies(request(GenerateMode.T2I, AdapterCatalog.SEEDREAM_5_0, AdapterCatalog.SEEDREAM_5_0_MODEL)));
        assertFalse(SeedreamOriginUrls.qualifies(request(GenerateMode.I2I, AdapterCatalog.SEEDREAM_5_0, "doubao-seedream-5.0-pro")));
        assertFalse(SeedreamOriginUrls.qualifies(request(GenerateMode.T2I, AdapterCatalog.SEEDREAM_4_5, "doubao-seedream-4-5-251128")));
    }

    @Test
    void signedUrlExpiresBeforeTheDayWindow() {
        LocalObjectStorage storage = new LocalObjectStorage(tempDir);
        String key = "users/1/outputs/9/1.png";
        String url = "https://cdn.example/lite.png?X-Tos-Date=20261009T030000Z&X-Tos-Expires=3600";
        SeedreamOriginUrls.save(storage, mapper, key, url, Instant.parse("2026-10-09T03:00:00Z"));

        assertEquals(url, SeedreamOriginUrls.resolve(storage, mapper, key, Instant.parse("2026-10-09T03:30:00Z")));
        assertNull(SeedreamOriginUrls.resolve(storage, mapper, key, Instant.parse("2026-10-09T04:00:00Z")));
    }

    private static GenerateRequest request(String mode, String adapter, String model) {
        return new GenerateRequest()
                .setMode(mode)
                .setAdapterKey(adapter)
                .setModelKey(model);
    }
}
