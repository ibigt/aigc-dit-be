package cn.aigc.dit.generate.ark;

import cn.aigc.dit.storage.LocalObjectStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ArkAssetRefsTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void activeUriOnlyWhenStatusIsActive() {
        LocalObjectStorage storage = new LocalObjectStorage(tempDir);
        String key = "users/7/gen/role.png";
        ArkAssetRefs.save(storage, mapper, key, new ArkAssetRefs.Ref("asset-1", "Active", "", "group-1"));

        assertEquals("asset://asset-1", ArkAssetRefs.activeUri(storage, mapper, key));

        ArkAssetRefs.save(storage, mapper, key, new ArkAssetRefs.Ref("asset-1", "Failed", "人脸不一致", "group-1"));
        assertNull(ArkAssetRefs.activeUri(storage, mapper, key));

        ArkAssetRefs.save(storage, mapper, key, new ArkAssetRefs.Ref("asset-1", "Processing", "", "group-1"));
        assertNull(ArkAssetRefs.activeUri(storage, mapper, key));
    }
}
