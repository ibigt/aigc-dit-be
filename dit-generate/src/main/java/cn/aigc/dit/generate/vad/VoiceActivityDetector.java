package cn.aigc.dit.generate.vad;

import java.nio.file.Path;
import java.util.List;

public interface VoiceActivityDetector {

    List<SpeechSegment> detect(Path audioFile);
}
