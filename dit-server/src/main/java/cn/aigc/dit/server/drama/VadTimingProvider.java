package cn.aigc.dit.server.drama;

import cn.aigc.dit.generate.vad.SpeechSegment;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

@Component
public class VadTimingProvider implements CaptionTimingProvider {

    @Override
    public TimingResult provide(Path audioFile, String dialogue, List<SpeechSegment> speechSegments) {
        List<SubtitleCue> captions = CaptionAligner.align(dialogue, speechSegments);
        return new TimingResult("VAD", 0D, 0D, captions, List.of(),
                captions.isEmpty() ? "VAD_NO_CAPTIONS" : null, "vad-1");
    }
}
