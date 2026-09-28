package cn.aigc.dit.generate.alignment;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class CharsiuForcedAlignmentServiceTest {

    @Test
    void alignsUsingTheOriginalIndicesOfNonSilenceFrames() {
        // The first two source frames are silence and must not influence the path.
        float[] probabilities = {
                0.9f, 0.1f, 0.0f,
                0.9f, 0.1f, 0.0f,
                0.0f, 0.9f, 0.1f,
                0.0f, 0.9f, 0.1f,
                0.0f, 0.1f, 0.9f
        };

        assertArrayEquals(new int[]{0, 0, 1},
                CharsiuForcedAlignmentService.forcedAlign(
                        probabilities, 3, List.of(2, 3, 4), List.of(1, 2)));
    }
}
