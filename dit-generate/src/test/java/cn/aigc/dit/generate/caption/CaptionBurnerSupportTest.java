package cn.aigc.dit.generate.caption;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptionBurnerSupportTest {

    @TempDir
    Path tempDir;

    @Test
    void wrapInsertsNewlineEverySixteenCodePoints() {
        assertEquals("短句", CaptionTexts.wrap("短句"));
        String longLine = "一二三四五六七八九十一二三四五六七八";
        String wrapped = CaptionTexts.wrap(longLine);
        assertTrue(wrapped.contains("\n"));
        assertEquals("一二三四五六七八九十一二三四五六\n七八", wrapped);
    }

    @Test
    void stripTrailingPunctuationRemovesDisplayEndingMarks() {
        assertEquals("科学解决不了的", CaptionTexts.stripTrailingPunctuation("科学解决不了的，"));
        assertEquals("找猫抓狗去居委会", CaptionTexts.stripTrailingPunctuation("找猫抓狗去居委会。"));
        assertEquals("你好", CaptionTexts.stripTrailingPunctuation("你好！！"));
        assertEquals("短句", CaptionTexts.wrap("短句，"));
    }

    @Test
    void toCaptionKeyKeepsDirectoryAndExtension() {
        assertEquals("users/1/out-caption.mp4", FfmpegVideoCaptionBurner.toCaptionKey("users/1/out.mp4"));
        assertTrue(FfmpegVideoCaptionBurner.alreadyCaptioned("users/1/out-caption.mp4"));
        assertFalse(FfmpegVideoCaptionBurner.alreadyCaptioned("users/1/out.mp4"));
        assertFalse(FfmpegVideoCaptionBurner.isVideo("users/1/out.png"));
    }

    @Test
    void drawtextFilterEscapesWindowsDriveAndTimesCues() {
        Path font = tempDir.resolve("font.ttf");
        Path text = tempDir.resolve("c0.txt");
        String filter = DrawtextFilter.build(font, List.of(new DrawtextFilter.Overlay(0, 2.5, text)));
        assertTrue(filter.startsWith("drawtext=fontfile='"));
        assertTrue(filter.contains("textfile='"));
        assertTrue(filter.contains("enable=between(t\\,0.000\\,2.500)"));
        assertTrue(filter.contains("expansion=none"));
        String escaped = DrawtextFilter.escapePath(Path.of("C:/Windows/Fonts/msyh.ttc"));
        assertTrue(escaped.contains("C\\:/Windows/Fonts/msyh.ttc"));
    }

    @Test
    void resolvePrefersConfiguredFontWhenFileExists() throws Exception {
        Path font = tempDir.resolve("custom.otf");
        Files.writeString(font, "font");
        assertEquals(font.toAbsolutePath().normalize(), CaptionFonts.resolve(font.toString()));
    }
}
