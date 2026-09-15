package cn.aigc.dit.server.drama;

import cn.aigc.dit.generate.AdapterCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DramaResolutionsTest {

    @Test
    void miniMaxH3SupportsUpTo2k() {
        assertEquals(DramaResolutions.OPTIONS,
                DramaResolutions.supported(AdapterCatalog.MINIMAX_H3, AdapterCatalog.MINIMAX_H3_MODEL));
        assertEquals("480P", DramaResolutions.clamp("480p", AdapterCatalog.MINIMAX_H3));
        assertEquals("2K", DramaResolutions.clamp("2k", AdapterCatalog.MINIMAX_H3, AdapterCatalog.MINIMAX_H3_MODEL));
    }

    @Test
    void miniMaxH3MaxCapsAt720p() {
        assertEquals(List.of("480P", "720P"),
                DramaResolutions.supported(AdapterCatalog.MINIMAX_H3, AdapterCatalog.MINIMAX_H3_MAX_MODEL));
        assertEquals("720P", DramaResolutions.clamp("2K", AdapterCatalog.MINIMAX_H3, AdapterCatalog.MINIMAX_H3_MAX_MODEL));
    }

    @Test
    void arkSeedance20Adds1080pButNever2k() {
        assertEquals(List.of("480P", "720P", "1080P"), DramaResolutions.supported(
                AdapterCatalog.SEEDANCE_2_0, AdapterCatalog.SEEDANCE_2_0, AdapterCatalog.SEEDANCE_2_0_MODEL));
        assertEquals("1080P", DramaResolutions.clamp("2K",
                AdapterCatalog.SEEDANCE_2_0, AdapterCatalog.SEEDANCE_2_0, AdapterCatalog.SEEDANCE_2_0_MODEL));
    }

    @Test
    void arkSeedance25CapsAt720p() {
        assertEquals(List.of("480P", "720P"), DramaResolutions.supported(AdapterCatalog.SEEDANCE_2_5));
        assertEquals("720P", DramaResolutions.clamp("1080P",
                AdapterCatalog.SEEDANCE_2_5, AdapterCatalog.SEEDANCE_2_5, AdapterCatalog.SEEDANCE_2_5_MODEL));
        assertEquals("480P", DramaResolutions.clamp("480p", AdapterCatalog.SEEDANCE_2_5));
    }

    @Test
    void runningHubSeedanceKeeps2kEvenWithDoubaoModelKey() {
        assertEquals(DramaResolutions.OPTIONS, DramaResolutions.supported(
                AdapterCatalog.SEEDANCE_2, AdapterCatalog.SEEDANCE_2, AdapterCatalog.SEEDANCE_2_0_MODEL));
    }

    @Test
    void agnesOnlyAllows720p() {
        assertFalse(DramaResolutions.supported(AdapterCatalog.AGNES_VIDEO_2_5).contains("480P"));
        assertTrue(DramaResolutions.supported("agnes-video-2.5-flash").contains("720P"));
        assertEquals("720P", DramaResolutions.clamp("480P", AdapterCatalog.AGNES_VIDEO_2_5));
        assertEquals("720P", DramaResolutions.clamp("2K", AdapterCatalog.AGNES_VIDEO_2_5));
    }

    @Test
    void missingValueFallsBackTo720p() {
        assertEquals("720P", DramaResolutions.clamp(null));
        assertEquals("720P", DramaResolutions.clamp("  ", AdapterCatalog.MINIMAX_H3));
        assertEquals("720P", DramaResolutions.clamp(null, AdapterCatalog.SEEDANCE_2_5));
    }
}
