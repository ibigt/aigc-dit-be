package cn.aigc.dit.generate.ffmpeg;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FfmpegSupportTest {

    @Test
    void parseDurationSecondsFromFfmpegLog() {
        assertEquals(5.02, FfmpegSupport.parseDurationSeconds(
                "Input #0, mov,mp4\n  Duration: 00:00:05.02, start: 0.000000, bitrate: 1200 kb/s\n"), 0.001);
        assertEquals(75.5, FfmpegSupport.parseDurationSeconds("Duration: 00:01:15.50"), 0.001);
        assertNull(FfmpegSupport.parseDurationSeconds("no duration here"));
    }
}
