package cn.aigc.dit.server.drama;

import cn.aigc.dit.generate.AdapterCatalog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DramaResolutionsTest {

    @Test
    void miniMaxKeepsBothContractOptions() {
        assertEquals(DramaResolutions.OPTIONS, DramaResolutions.supported(AdapterCatalog.MINIMAX_H3));
        assertEquals("480P", DramaResolutions.clamp("480p", AdapterCatalog.MINIMAX_H3));
    }

    @Test
    void agnesOnlyAllows720p() {
        assertFalse(DramaResolutions.supported(AdapterCatalog.AGNES_VIDEO_2_5).contains("480P"));
        assertTrue(DramaResolutions.supported("agnes-video-2.5-flash").contains("720P"));
        assertEquals("720P", DramaResolutions.clamp("480P", AdapterCatalog.AGNES_VIDEO_2_5));
    }

    @Test
    void missingValueFallsBackTo720p() {
        assertEquals("720P", DramaResolutions.clamp(null));
        assertEquals("720P", DramaResolutions.clamp("1080P"));
        assertEquals(DramaResolutions.OPTIONS, DramaResolutions.supported(AdapterCatalog.SEEDANCE_2_5));
        assertEquals("480P", DramaResolutions.clamp("480p", AdapterCatalog.SEEDANCE_2_5));
    }
}
