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
    void derivesTextToAudio() {
        assertEquals(GenerateMode.T2A, GenerateMode.derive("audio", List.of(), null));
        assertTrue(GenerateMode.isSpecificMode("t2a"));
    }

    @Test
    void derivesReferenceToVideoWhenHasImageOrVideoReference() {
        assertEquals(GenerateMode.R2V, GenerateMode.derive("video",
                List.of(new ReferenceAsset("image", "users/1/assets/a.png")), null));
        assertEquals(GenerateMode.R2V, GenerateMode.derive("video",
                List.of(new ReferenceAsset("video", "users/1/assets/a.mp4")), null));
    }

    @Test
    void i2vWinsOverReferences() {
        FramePair frames = new FramePair("a.png", "b.png");
        assertEquals(GenerateMode.I2V, GenerateMode.derive("video",
                List.of(new ReferenceAsset("image", "x.png")), frames));
    }

    @Test
    void i2vAllowsBlankPromptAndFillsDefault() {
        assertTrue(GenerateMode.allowBlankPrompt(new FramePair("a.png", null)));
        assertEquals(GenerateMode.DEFAULT_I2V_PROMPT, GenerateMode.i2vPrompt("  "));
        assertEquals("转身", GenerateMode.i2vPrompt(" 转身 "));
    }

    @Test
    void canonicalizesLegacyVideoModes() {
        assertEquals(GenerateMode.I2V, GenerateMode.canonical("i2v"));
        assertEquals(GenerateMode.I2V, GenerateMode.canonical("v2v"));
        assertEquals(GenerateMode.I2V, GenerateMode.canonical("frames"));
        assertEquals(GenerateMode.I2V, GenerateMode.canonical("frame"));
        assertEquals(List.of("video", "t2v", "r2v", "i2v"),
                GenerateMode.canonicalizeCapabilities(List.of("video", "t2v", "r2v", "v2v", "frames")));
        assertTrue(GenerateMode.isVideo("i2v"));
        assertTrue(GenerateMode.isVideo("v2v"));
    }
}
