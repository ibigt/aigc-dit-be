package cn.aigc.dit.server.drama;

import cn.aigc.dit.generate.vad.SpeechSegment;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CaptionTimingResolverTest {

    @Test
    void doesNotEstimateWhenSpeechEmptyAndFullAudioAlignmentFails() {
        ForcedAlignmentTimingProvider forced = mock(ForcedAlignmentTimingProvider.class);
        VadTimingProvider vad = mock(VadTimingProvider.class);
        EstimatedTimingProvider estimated = mock(EstimatedTimingProvider.class);
        Path audio = Path.of("audio.wav");
        TimingResult failedAlignment = timing("FORCED_ALIGNMENT", List.of(), "ALIGNMENT_LOW_CONFIDENCE_WITHOUT_VAD");
        when(forced.provide(audio, "台词", List.of())).thenReturn(failedAlignment);

        CaptionTimingResolver.Resolution resolution =
                new CaptionTimingResolver(forced, vad, estimated).resolve(audio, "台词", List.of());

        assertFalse(resolution.timing().usable());
        assertTrue(resolution.fallbackReason().contains("NO_SPEECH_NO_ESTIMATED"));
        assertTrue(resolution.fallbackReason().contains("ALIGNMENT_LOW_CONFIDENCE_WITHOUT_VAD"));
        verify(vad, never()).provide(any(), anyString(), any());
        verify(estimated, never()).provide(any(), anyString(), any());
    }

    @Test
    void fallsBackToEstimatedWhenSpeechPresentAndVadAndAlignmentFail() {
        ForcedAlignmentTimingProvider forced = mock(ForcedAlignmentTimingProvider.class);
        VadTimingProvider vad = mock(VadTimingProvider.class);
        EstimatedTimingProvider estimated = mock(EstimatedTimingProvider.class);
        Path audio = Path.of("audio.wav");
        List<SpeechSegment> speech = List.of(new SpeechSegment(100, 900));
        TimingResult failedAlignment = timing("FORCED_ALIGNMENT", List.of(), "ALIGNMENT_LOW_CONFIDENCE");
        TimingResult failedVad = timing("VAD", List.of(), "VAD_NO_CAPTIONS");
        TimingResult estimatedResult = timing("ESTIMATED", List.of(new SubtitleCue(0, 1000, "台词")), null);
        when(forced.provide(audio, "台词", speech)).thenReturn(failedAlignment);
        when(vad.provide(audio, "台词", speech)).thenReturn(failedVad);
        when(estimated.provide(audio, "台词", speech)).thenReturn(estimatedResult);

        CaptionTimingResolver.Resolution resolution =
                new CaptionTimingResolver(forced, vad, estimated).resolve(audio, "台词", speech);

        assertEquals("ESTIMATED", resolution.timing().sourceType());
        assertTrue(resolution.timing().usable());
        assertEquals("ALIGNMENT_LOW_CONFIDENCE;VAD_NO_CAPTIONS;ESTIMATED_TIMING_FALLBACK",
                resolution.fallbackReason());
    }

    @Test
    void keepsSuccessfulFullAudioAlignmentAheadOfEstimatedFallback() {
        ForcedAlignmentTimingProvider forced = mock(ForcedAlignmentTimingProvider.class);
        VadTimingProvider vad = mock(VadTimingProvider.class);
        EstimatedTimingProvider estimated = mock(EstimatedTimingProvider.class);
        Path audio = Path.of("audio.wav");
        TimingResult aligned = timing("FORCED_ALIGNMENT_FULL_AUDIO",
                List.of(new SubtitleCue(100, 900, "台词")), null);
        when(forced.provide(audio, "台词", List.of())).thenReturn(aligned);

        CaptionTimingResolver.Resolution resolution =
                new CaptionTimingResolver(forced, vad, estimated).resolve(audio, "台词", List.of());

        assertEquals("FORCED_ALIGNMENT_FULL_AUDIO", resolution.timing().sourceType());
        assertEquals("VAD_EMPTY_FULL_AUDIO_ALIGNMENT", resolution.fallbackReason());
        verify(vad, never()).provide(any(), anyString(), any());
        verify(estimated, never()).provide(any(), anyString(), any());
    }

    private static TimingResult timing(String source, List<SubtitleCue> captions, String failureReason) {
        return new TimingResult(source, 0D, 0D, captions, List.of(), failureReason, "test-1");
    }
}
