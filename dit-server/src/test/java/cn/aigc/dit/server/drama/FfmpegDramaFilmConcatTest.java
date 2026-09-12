package cn.aigc.dit.server.drama;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FfmpegDramaFilmConcatTest {

    @TempDir
    Path tempDir;

    @Test
    void concatListKeepsInputOrderAndEscapesQuotes() {
        Path first = tempDir.resolve("shot-1.mp4");
        Path second = tempDir.resolve("shot-2.mp4");
        String list = FfmpegDramaFilmConcat.concatList(List.of(first, second));
        assertTrue(list.indexOf("shot-1.mp4") < list.indexOf("shot-2.mp4"));
        assertTrue(list.startsWith("file '"));
    }
}
