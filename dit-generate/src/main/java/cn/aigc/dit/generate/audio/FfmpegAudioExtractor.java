package cn.aigc.dit.generate.audio;

import cn.aigc.dit.generate.ffmpeg.FfmpegSupport;

import java.nio.file.Path;
import java.util.List;

/** Extracts the final video audio in the format expected by Silero VAD. */
public final class FfmpegAudioExtractor {

    private FfmpegAudioExtractor() {
    }

    public static void extractPcm16Mono(Path video, Path wav, int timeoutSeconds) {
        FfmpegSupport.run(List.of(
                "-i", video.toString(),
                "-vn",
                "-ac", "1",
                "-ar", "16000",
                "-c:a", "pcm_s16le",
                wav.toString()), timeoutSeconds);
    }
}
