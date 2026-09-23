package cn.aigc.dit.server.drama;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AlignmentTextNormalizerTest {

    @Test
    void removesSpeakerPunctuationAndNormalizesDigitsWithoutChangingDisplayText() {
        AlignmentTextNormalizer.NormalizedText result = AlignmentTextNormalizer.normalizeDialogue(
                "局长：洛老弟，别睡了。\n半个月内5个人！");

        assertEquals("洛老弟，别睡了。半个月内5个人！", result.rawText());
        assertEquals("洛老弟别睡了半个月内五个人", result.normalizedText());
    }
}
