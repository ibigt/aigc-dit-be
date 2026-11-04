package cn.aigc.dit.server.credit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class VideoTiersTest {

    @Test
    void normalizeKeepsKnownVideoTiersOnly() {
        assertEquals("480P", VideoTiers.normalize("480p"));
        assertEquals("1080P", VideoTiers.normalize(" 1080P "));
        assertEquals("2K", VideoTiers.normalize("2k"));
        // 图片清晰度（1K）与未知名不是视频档
        assertNull(VideoTiers.normalize("1K"));
        assertNull(VideoTiers.normalize(null));
    }

    @Test
    void shortSideMapsPixelSizeToTier() {
        assertEquals("480P", VideoTiers.byShortSide(854, 480));
        assertEquals("720P", VideoTiers.byShortSide(1280, 720));
        assertEquals("768P", VideoTiers.byShortSide(1280, 768));
        assertEquals("1080P", VideoTiers.byShortSide(1920, 1080));
        assertEquals("2K", VideoTiers.byShortSide(2560, 1440));
        assertEquals("4K", VideoTiers.byShortSide(3840, 2160));
        // 竖版按短边（宽）折档
        assertEquals("1080P", VideoTiers.byShortSide(1080, 1920));
        assertNull(VideoTiers.byShortSide(0, 480));
    }

    @Test
    void highestKeepsThePricierTier() {
        assertEquals("1080P", VideoTiers.highest("480P", "1080P"));
        assertEquals("1080P", VideoTiers.highest("1080P", "480P"));
        assertEquals("720P", VideoTiers.highest(null, "720P"));
        assertEquals("720P", VideoTiers.highest("720P", null));
    }
}
