package cn.aigc.dit.generate.seedream;

import cn.aigc.dit.generate.AdapterCatalog;
import cn.aigc.dit.generate.GenerateRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeedreamRequestMapperTest {

    @Test
    void modelNamePrefersConfiguredKey() {
        GenerateRequest request = new GenerateRequest();
        request.setAdapterKey(AdapterCatalog.SEEDREAM_5_0);
        assertEquals(AdapterCatalog.SEEDREAM_5_0_MODEL, SeedreamRequestMapper.modelName(request));

        request.setModelKey("doubao-seedream-5.0-pro");
        assertEquals("doubao-seedream-5.0-pro", SeedreamRequestMapper.modelName(request));

        request.setAdapterKey(AdapterCatalog.SEEDREAM_4_5);
        request.setModelKey(null);
        assertEquals(AdapterCatalog.SEEDREAM_4_5_MODEL, SeedreamRequestMapper.modelName(request));
    }

    @Test
    void toBodyMapsT2iAndI2i() {
        GenerateRequest request = new GenerateRequest();
        request.setAdapterKey(AdapterCatalog.SEEDREAM_4_0);
        request.setModelKey(AdapterCatalog.SEEDREAM_4_0_MODEL);
        request.setPrompt("a quiet room");
        request.setResolution("2K");
        request.setRatio("16:9");

        Map<String, Object> t2i = SeedreamRequestMapper.toBody(request, List.of());
        assertEquals(AdapterCatalog.SEEDREAM_4_0_MODEL, t2i.get("model"));
        assertEquals("a quiet room", t2i.get("prompt"));
        assertEquals("2560x1440", t2i.get("size"));
        assertEquals("url", t2i.get("response_format"));
        assertEquals(false, t2i.get("watermark"));
        assertFalse(t2i.containsKey("image"));

        Map<String, Object> single = SeedreamRequestMapper.toBody(request, List.of("data:image/png;base64,abc"));
        assertEquals("data:image/png;base64,abc", single.get("image"));

        Map<String, Object> multi = SeedreamRequestMapper.toBody(
                request, List.of("data:image/png;base64,a", "data:image/png;base64,b"));
        assertInstanceOf(List.class, multi.get("image"));
        assertEquals(2, ((List<?>) multi.get("image")).size());
    }

    @Test
    void imageSizeFallsBackToTier() {
        assertEquals("2K", SeedreamRequestMapper.imageSize("2K", null));
        assertEquals("4K", SeedreamRequestMapper.imageSize("4K", ""));
        assertEquals("2048x2048", SeedreamRequestMapper.imageSize("1K", "1:1"));
        assertTrue(SeedreamRequestMapper.imageSize("4K", "9:16").startsWith("2160"));
    }
}
