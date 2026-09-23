package cn.aigc.dit.server.drama;

import cn.aigc.dit.generate.audio.Pcm16Audio;
import cn.aigc.dit.generate.vad.SpeechSegment;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/** Last-resort timing provider retained for audio/VAD edge cases. */
@Component
public class EstimatedTimingProvider implements CaptionTimingProvider {

    @Override
    public TimingResult provide(Path audioFile, String dialogue, List<SpeechSegment> speechSegments) {
        try {
            long durationMs = Pcm16Audio.read(audioFile, 16000).durationMs();
            List<SubtitleCue> captions = CaptionAligner.estimated(dialogue, durationMs);
            return new TimingResult("ESTIMATED", 0D, 0D, captions, List.of(),
                    captions.isEmpty() ? "ESTIMATED_NO_CAPTIONS" : null, "estimated-1");
        } catch (Exception e) {
            return new TimingResult("ESTIMATED", 0D, 0D, List.of(), List.of(),
                    "ESTIMATED_FAILED", "estimated-1");
        }
    }
}
