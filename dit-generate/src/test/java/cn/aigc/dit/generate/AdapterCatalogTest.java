package cn.aigc.dit.generate;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdapterCatalogTest {

    @Test
    void infersFromProviderAndCategory() {
        assertEquals(AdapterCatalog.MINIMAX_H3, AdapterCatalog.infer("ComfyUI", "video", "minimax.h3"));
        assertEquals(AdapterCatalog.SEEDANCE_2, AdapterCatalog.infer("RunningHub", "video", "seedance-2.0"));
        assertEquals(AdapterCatalog.GPT_IMAGE_2, AdapterCatalog.infer("RunningHub", "image", "gpt-image-2"));
        assertEquals(AdapterCatalog.MOCK, AdapterCatalog.infer("mock", "video", "mock-video"));
    }

    @Test
    void seedanceDoesNotSupportI2v() {
        assertTrue(AdapterCatalog.supportsMode(AdapterCatalog.SEEDANCE_2, GenerateMode.T2V));
        assertTrue(AdapterCatalog.supportsMode(AdapterCatalog.SEEDANCE_2, GenerateMode.R2V));
        assertFalse(AdapterCatalog.supportsMode(AdapterCatalog.SEEDANCE_2, GenerateMode.I2V));
        assertTrue(AdapterCatalog.supportsMode(AdapterCatalog.MINIMAX_H3, GenerateMode.I2V));
    }

    @Test
    void constrainsCapabilitiesToAdapterModes() {
        assertEquals(List.of("video", "t2v", "r2v"),
                AdapterCatalog.constrainCapabilities(
                        List.of("video", "t2v", "r2v", "i2v"),
                        "video",
                        AdapterCatalog.require(AdapterCatalog.SEEDANCE_2)));
    }
}
