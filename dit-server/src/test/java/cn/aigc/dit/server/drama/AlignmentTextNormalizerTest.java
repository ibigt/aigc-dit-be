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

    @Test
    void normalizesNumberRunsAndEnglishInitialismsForMandarinAlignment() {
        AlignmentTextNormalizer.NormalizedText result = AlignmentTextNormalizer.normalizeDialogue(
                "苏雅：这是第147次循环，中午12点，负载900%，IP和AI异常。");

        assertEquals("这是第147次循环，中午12点，负载900%，IP和AI异常。", result.rawText());
        assertEquals("这是第一百四十七次循环中午十二点负载九百艾批和诶艾异常", result.normalizedText());
    }
}
