package cn.aigc.dit.generate.audio;

import cn.aigc.dit.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfmpegAudioReferenceProcessorTest {

    @Test
    void commandLimitsDurationAndNormalizesMp3() {
        List<String> args = FfmpegAudioReferenceProcessor.commandArgs(
                Path.of("input.wav"), Path.of("output.mp3"));

        assertEquals("14.8", args.get(args.indexOf("-t") + 1));
        assertEquals("1", args.get(args.indexOf("-ac") + 1));
        assertEquals("24000", args.get(args.indexOf("-ar") + 1));
        assertEquals("libmp3lame", args.get(args.indexOf("-codec:a") + 1));
    }

    @Test
    void emptyAudioIsRejectedBeforeFfmpeg() {
        BizException error = assertThrows(BizException.class,
                () -> new FfmpegAudioReferenceProcessor().process(new byte[0], "empty.wav"));

        assertTrue(error.getMessage().contains("参考音频文件为空"));
    }
}
