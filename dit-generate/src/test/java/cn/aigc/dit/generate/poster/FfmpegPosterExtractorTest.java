package cn.aigc.dit.generate.poster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfmpegPosterExtractorTest {

    @Test
    void tailKeySitsBesidePosterKey() {
        assertEquals("users/7/gen/shot-poster.webp", FfmpegPosterExtractor.toPosterKey("users/7/gen/shot.mp4"));
        assertEquals("users/7/gen/shot-tail.webp", FfmpegPosterExtractor.toTailKey("users/7/gen/shot.mp4"));
        assertTrue(FfmpegPosterExtractor.isImage("a.webp"));
    }
}
