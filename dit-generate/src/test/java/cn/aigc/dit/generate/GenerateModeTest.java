package cn.aigc.dit.generate;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerateModeTest {

    @Test
    void derivesTextToImageWhenNoRefs() {
        assertEquals(GenerateMode.T2I, GenerateMode.derive("image", List.of(), null));
    }

    @Test
    void derivesReferenceToVideoWhenHasImageOrVideoReference() {
        assertEquals(GenerateMode.R2V, GenerateMode.derive("video",
                List.of(new ReferenceAsset("image", "users/1/assets/a.png")), null));
        assertEquals(GenerateMode.R2V, GenerateMode.derive("video",
                List.of(new ReferenceAsset("video", "users/1/assets/a.mp4")), null));
    }

    @Test
    void v2vWinsOverReferences() {
        FramePair frames = new FramePair("a.png", "b.png");
        assertEquals(GenerateMode.V2V, GenerateMode.derive("video",
                List.of(new ReferenceAsset("image", "x.png")), frames));
    }

    @Test
    void canonicalizesLegacyVideoModes() {
        assertEquals(GenerateMode.R2V, GenerateMode.canonical("i2v"));
        assertEquals(GenerateMode.V2V, GenerateMode.canonical("frames"));
        assertEquals(GenerateMode.V2V, GenerateMode.canonical("frame"));
        assertEquals(List.of("video", "t2v", "r2v", "v2v"),
                GenerateMode.canonicalizeCapabilities(List.of("video", "t2v", "i2v", "frames", "r2v")));
        assertTrue(GenerateMode.isVideo("i2v"));
        assertTrue(GenerateMode.isVideo("v2v"));
    }
}
