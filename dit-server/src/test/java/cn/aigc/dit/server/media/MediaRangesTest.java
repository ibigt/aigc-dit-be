package cn.aigc.dit.server.media;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpRange;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaRangesTest {

    @Test
    void noRangeReturnsWholeFile() {
        MediaRanges.Slice slice = MediaRanges.resolve(8, List.of());
        assertEquals(0, slice.start());
        assertEquals(7, slice.end());
        assertEquals(8, slice.length());
        assertFalse(slice.partial());
    }

    @Test
    void closedRangeIsPartial() {
        MediaRanges.Slice slice = MediaRanges.resolve(8, HttpRange.parseRanges("bytes=2-5"));
        assertEquals(2, slice.start());
        assertEquals(5, slice.end());
        assertEquals(4, slice.length());
        assertTrue(slice.partial());
    }

    @Test
    void openEndedRangeGoesToEof() {
        MediaRanges.Slice slice = MediaRanges.resolve(8, HttpRange.parseRanges("bytes=5-"));
        assertEquals(5, slice.start());
        assertEquals(7, slice.end());
        assertTrue(slice.partial());
    }

    @Test
    void unsatisfiableRangeThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> MediaRanges.resolve(8, HttpRange.parseRanges("bytes=20-30")));
    }
}
