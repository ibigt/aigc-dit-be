package cn.aigc.dit.generate;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdapterCatalogTest {

    @Test
    void infersFromProviderAndCategory() {
        assertEquals(AdapterCatalog.AGNES_IMAGE_2_5, AdapterCatalog.infer("官方API", "image", "agnes-image-2.5-flash"));
        assertEquals(AdapterCatalog.AGNES_VIDEO_2_5, AdapterCatalog.infer("官方API", "video", "agnes-video-2.5-flash"));
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
        assertTrue(AdapterCatalog.supportsMode(AdapterCatalog.MINIMAX_H3, GenerateMode.R2V));
        assertTrue(AdapterCatalog.supportsMode(AdapterCatalog.AGNES_IMAGE_2_5, GenerateMode.T2I));
        assertTrue(AdapterCatalog.supportsMode(AdapterCatalog.AGNES_IMAGE_2_5, GenerateMode.I2I));
        assertTrue(AdapterCatalog.supportsMode(AdapterCatalog.AGNES_VIDEO_2_5, GenerateMode.I2V));
        assertTrue(AdapterCatalog.isAgnes(AdapterCatalog.AGNES_VIDEO_2_5));
        assertEquals("https://api.agnes-ai.cn/v1", AdapterCatalog.require(AdapterCatalog.AGNES_IMAGE_2_5).defaultEndpoint());
        assertEquals("官方API", AdapterCatalog.require(AdapterCatalog.AGNES_VIDEO_2_5).providerLabel());
        assertEquals("http://127.0.0.1:8188", AdapterCatalog.require(AdapterCatalog.MINIMAX_H3).defaultEndpoint());
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
