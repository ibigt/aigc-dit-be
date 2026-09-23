package cn.aigc.dit.server.drama;

import cn.aigc.dit.generate.vad.SpeechSegment;

import java.nio.file.Path;
import java.util.List;

public interface CaptionTimingProvider {

    TimingResult provide(Path audioFile, String dialogue, List<SpeechSegment> speechSegments);
}
