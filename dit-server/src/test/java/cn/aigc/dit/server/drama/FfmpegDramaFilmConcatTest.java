package cn.aigc.dit.server.drama;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void smoothArgsNormalizesPortraitFrameAndFadesAudioWithoutCopy() {
        Path first = tempDir.resolve("shot-1.mp4");
        Path second = tempDir.resolve("shot-2.mp4");
        Path output = tempDir.resolve("film.mp4");
        List<String> args = FfmpegDramaFilmConcat.smoothArgs(
                List.of(first, second),
                List.of(new DramaFilmModes.ClipSpan(0D, 0.2D, 5D), new DramaFilmModes.ClipSpan(0.2D, 0D, 4D)),
                "9:16",
                List.of(true, false),
                output);
        String filter = args.get(args.indexOf("-filter_complex") + 1);

        assertArrayEquals(new int[] {720, 1280}, FfmpegDramaFilmConcat.frameSize("9:16"));
        assertArrayEquals(new int[] {1280, 720}, FfmpegDramaFilmConcat.frameSize("16:9"));
        assertTrue(filter.contains("scale=720:1280"));
        assertTrue(filter.contains("pad=720:1280"));
        assertTrue(filter.contains("fps=24"));
        assertTrue(filter.contains("format=yuv420p"));
        assertTrue(filter.contains("atrim=start=0.000:end=4.800"));
        assertTrue(filter.contains("atrim=duration=3.800"));
        assertTrue(filter.contains("afade=t=out"));
        assertTrue(filter.contains("afade=t=in"));
        assertTrue(filter.contains("aresample=48000"));
        assertTrue(args.contains("anullsrc=channel_layout=stereo:sample_rate=48000"));
        assertFalse(args.contains("copy"));
        assertTrue(args.contains("libx264"));
        assertTrue(args.contains(output.toString()));
    }
}
