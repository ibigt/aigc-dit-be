package cn.aigc.dit.generate;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenerateModeTest {

    @Test
    void derivesTextToImageWhenNoRefs() {
        assertEquals(GenerateMode.T2I, GenerateMode.derive("image", List.of(), null));
    }

    @Test
    void derivesImageToVideoWhenHasReference() {
        assertEquals(GenerateMode.I2V, GenerateMode.derive("video",
                List.of(new ReferenceAsset("image", "users/1/assets/a.png")), null));
    }

    @Test
    void framesWinOverReferences() {
        FramePair frames = new FramePair("a.png", "b.png");
        assertEquals(GenerateMode.FRAMES, GenerateMode.derive("video",
                List.of(new ReferenceAsset("image", "x.png")), frames));
    }
}
