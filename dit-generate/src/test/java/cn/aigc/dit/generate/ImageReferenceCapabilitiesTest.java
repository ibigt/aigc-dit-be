package cn.aigc.dit.generate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageReferenceCapabilitiesTest {

    @Test
    void matchesVendorImageCaps() {
        assertEquals(30, ImageReferenceCapabilities.maxImages(
                AdapterCatalog.SEEDANCE_2_5, "官方API", "video", AdapterCatalog.SEEDANCE_2_5_MODEL));
        assertEquals(9, ImageReferenceCapabilities.maxImages(
                AdapterCatalog.SEEDANCE_2_0, "官方API", "video", "doubao-seedance-2.0-fast"));
        assertEquals(9, ImageReferenceCapabilities.maxImages(
                AdapterCatalog.MINIMAX_H3, "官方API", "video", AdapterCatalog.MINIMAX_H3_MODEL));
        assertEquals(5, ImageReferenceCapabilities.maxImages(
                AdapterCatalog.AGNES_VIDEO_2_5, "官方API", "video", AdapterCatalog.AGNES_VIDEO_MODEL));
        assertEquals(10, ImageReferenceCapabilities.maxImages(
                AdapterCatalog.WAN3, "官方API", "video", AdapterCatalog.WAN3_VIDEO_MODEL));
        assertEquals(ImageReferenceCapabilities.DEFAULT_MAX_IMAGES, ImageReferenceCapabilities.maxImages(
                AdapterCatalog.SEEDANCE_2, "RunningHub", "video", "seedance-2"));
        assertTrue(ImageReferenceCapabilities.maxImages(
                AdapterCatalog.AGNES_VIDEO_2_5, "官方API", "video", "agnes-video-2.5") > 5);
    }
}
