package cn.aigc.dit.generate.comfyui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ComfyUiGeometryTest {

    @Test
    void fiveSecondsAlignsTo124Frames() {
        assertEquals(5, ComfyUiGeometry.durationSec(3));
        assertEquals(15, ComfyUiGeometry.durationSec(20));
        assertEquals(124, ComfyUiGeometry.frameCount(5));
        assertEquals(124 % 17, 5);
    }

    @Test
    void defaultSixteenByNineUses864x480() {
        assertArrayEquals(new int[]{864, 480}, ComfyUiGeometry.size("16:9", null));
        assertArrayEquals(new int[]{480, 864}, ComfyUiGeometry.size("9:16", null));
        assertArrayEquals(new int[]{1344, 768}, ComfyUiGeometry.size("16:9", "1080P"));
    }

    @Test
    void ultraWideKeepsTwentyOneByNine() {
        assertArrayEquals(new int[]{1344, 576}, ComfyUiGeometry.size("21:9", "1080P"));
        assertArrayEquals(new int[]{864, 352}, ComfyUiGeometry.size("21:9", null));
    }
}
