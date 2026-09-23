package cn.aigc.dit.server.drama;

import cn.aigc.dit.generate.vad.SpeechSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptionAlignerTest {

    @Test
    void mapsPhrasesOneToOneWhenSpeechIntervalsMatch() {
        List<SubtitleCue> cues = CaptionAligner.align(
                "Narrator: Hello, world.",
                List.of(new SpeechSegment(720, 2480), new SpeechSegment(3010, 4280)));

        assertEquals(List.of(
                new SubtitleCue(720, 2480, "Hello,"),
                new SubtitleCue(3010, 4280, "world.")), cues);
    }

    @Test
    void neverCreatesCueAcrossDetectedSilence() {
        List<SubtitleCue> cues = CaptionAligner.align(
                "Narrator: One, two, three.",
                List.of(new SpeechSegment(720, 1200), new SpeechSegment(1800, 2400)));

        assertTrue(cues.stream().allMatch(cue ->
                (cue.startMs() >= 720 && cue.endMs() <= 1200)
                        || (cue.startMs() >= 1800 && cue.endMs() <= 2400)));
    }
}
