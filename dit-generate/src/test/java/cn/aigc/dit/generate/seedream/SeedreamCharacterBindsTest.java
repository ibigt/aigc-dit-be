package cn.aigc.dit.generate.seedream;

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

class SeedreamCharacterBindsTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void saveReadAndThirtyDayWindow() {
        LocalObjectStorage storage = new LocalObjectStorage(tempDir);
        String original = "users/7/outputs/249/1.png";
        Instant boundAt = Instant.parse("2026-10-01T00:00:00Z");
        SeedreamCharacterBinds.save(storage, mapper, original, SeedreamCharacterBinds.imageKey(original), boundAt);

        SeedreamCharacterBinds.Bind bind = SeedreamCharacterBinds.read(storage, mapper, original);
        assertEquals(SeedreamCharacterBinds.imageKey(original), bind.imageKey());
        assertEquals(boundAt, bind.boundAt());
        assertTrue(SeedreamCharacterBinds.fresh(bind, boundAt.plus(SeedreamCharacterBinds.BIND_TTL).minusSeconds(1)));
        assertFalse(SeedreamCharacterBinds.fresh(bind, boundAt.plus(SeedreamCharacterBinds.BIND_TTL)));
        assertNull(SeedreamCharacterBinds.read(storage, mapper, "missing.png"));
    }

    @Test
    void deleteAllRemovesImageOriginAndBind() {
        LocalObjectStorage storage = new LocalObjectStorage(tempDir);
        String original = "users/7/outputs/249/1.png";
        storage.put(original, new byte[]{1}, "image/png");
        storage.put(SeedreamCharacterBinds.imageKey(original), new byte[]{2}, "image/png");
        SeedreamOriginUrls.save(storage, mapper, SeedreamCharacterBinds.imageKey(original),
                "https://cdn.example/sheet.png", Instant.parse("2026-10-01T00:00:00Z"));
        SeedreamCharacterBinds.save(storage, mapper, original, SeedreamCharacterBinds.imageKey(original),
                Instant.parse("2026-10-01T00:00:00Z"));

        SeedreamCharacterBinds.deleteAll(storage, original);

        assertTrue(storage.exists(original));
        assertFalse(storage.exists(SeedreamCharacterBinds.imageKey(original)));
        assertFalse(storage.exists(SeedreamCharacterBinds.originKey(original)));
        assertFalse(storage.exists(SeedreamCharacterBinds.bindKey(original)));
        assertEquals("users/7/outputs/249/1.png.seedream.png.origin.json", SeedreamCharacterBinds.originKey(original));
    }
}
