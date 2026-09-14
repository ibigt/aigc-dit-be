package cn.aigc.dit.server.drama;

import cn.aigc.dit.generate.AdapterCatalog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DramaDurationsTest {

    @Test
    void miniMaxKeepsFifteen() {
        assertTrue(DramaDurations.supported(AdapterCatalog.MINIMAX_H3).contains(15));
        assertEquals(15, DramaDurations.clamp(15, AdapterCatalog.MINIMAX_H3));
    }

    @Test
    void agnesHidesFifteenAndClampsDown() {
        assertFalse(DramaDurations.supported(AdapterCatalog.AGNES_VIDEO_2_5).contains(15));
        assertEquals(12, DramaDurations.clamp(15, AdapterCatalog.AGNES_VIDEO_2_5));
        assertEquals(12, DramaDurations.clamp(12, "agnes-video-2.5-flash"));
    }

    @Test
    void defaultRangeKeepsContractOptions() {
        assertEquals(DramaDurations.OPTIONS, DramaDurations.supported("seedance-2"));
        assertEquals(DramaDurations.OPTIONS, DramaDurations.supported(AdapterCatalog.SEEDANCE_2_5));
        assertEquals(15, DramaDurations.clamp(null));
    }
}
