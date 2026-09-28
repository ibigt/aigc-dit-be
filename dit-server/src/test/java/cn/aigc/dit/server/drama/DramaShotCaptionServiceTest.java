package cn.aigc.dit.server.drama;

import cn.aigc.dit.generate.GenerateProperties;
import cn.aigc.dit.generate.caption.CaptionCue;
import cn.aigc.dit.generate.caption.VideoCaptionBurner;
import cn.aigc.dit.server.drama.entity.DramaShot;
import cn.aigc.dit.server.drama.mapper.DramaShotMapper;
import cn.aigc.dit.server.entity.GenTask;
import cn.aigc.dit.server.generate.GenerateRequests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DramaShotCaptionServiceTest {

    @Mock
    private DramaShotMapper shotMapper;
    @Mock
    private VideoCaptionBurner videoCaptionBurner;

    private GenerateProperties properties;
    private DramaShotCaptionService service;

    @BeforeEach
    void setUp() {
        properties = new GenerateProperties();
        properties.getFfmpeg().setCaptionBurnEnabled(true);
        service = new DramaShotCaptionService(shotMapper, videoCaptionBurner, properties);
    }

    @Test
    void skipsNonDramaTasks() {
        GenTask task = new GenTask();
        task.setParams(Map.of("source", GenerateRequests.SOURCE));
        List<String> keys = List.of("users/1/out.mp4");
        assertEquals(keys, service.burnIfNeeded(task, keys));
        verify(videoCaptionBurner, never()).burn(any(), any());
    }

    @Test
    void skipsWhenNoSpokenDialogue() {
        GenTask task = dramaTask(9L);
        DramaShot shot = new DramaShot();
        shot.setId(9L);
        shot.setDialogue("局长：  ");
        shot.setDurationSec(5);
        when(shotMapper.selectById(9L)).thenReturn(shot);
        List<String> keys = List.of("users/1/shot.mp4");
        assertEquals(keys, service.burnIfNeeded(task, keys));
        verify(videoCaptionBurner, never()).burn(any(), any());
    }

    @Test
    void burnsCaptionedKeyWhenDialoguePresent() {
        GenTask task = dramaTask(9L);
        DramaShot shot = new DramaShot();
        shot.setId(9L);
        shot.setDialogue("局长：洛老弟，别睡了。");
        shot.setDurationSec(5);
        when(shotMapper.selectById(9L)).thenReturn(shot);
        when(videoCaptionBurner.burn(eq("users/1/shot.mp4"), any()))
                .thenReturn("users/1/shot-caption.mp4");

        List<String> out = service.burnIfNeeded(task, List.of("users/1/shot.mp4"));
        assertEquals(List.of("users/1/shot-caption.mp4"), out);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CaptionCue>> cues = ArgumentCaptor.forClass(List.class);
        verify(videoCaptionBurner).burn(eq("users/1/shot.mp4"), cues.capture());
        assertEquals(1, cues.getValue().size());
        assertEquals("洛老弟，别睡了。", cues.getValue().get(0).text());
        assertEquals(5.0, cues.getValue().get(0).endSec());
    }

    @Test
    void keepsOriginalWhenBurnThrows() {
        GenTask task = dramaTask(9L);
        DramaShot shot = new DramaShot();
        shot.setId(9L);
        shot.setDialogue("洛白：走。");
        shot.setDurationSec(5);
        when(shotMapper.selectById(9L)).thenReturn(shot);
        when(videoCaptionBurner.burn(any(), any())).thenThrow(new RuntimeException("ffmpeg"));
        List<String> keys = List.of("users/1/shot.mp4");
        assertEquals(keys, service.burnIfNeeded(task, keys));
    }

    @Test
    void respectsCaptionBurnDisabled() {
        properties.getFfmpeg().setCaptionBurnEnabled(false);
        GenTask task = dramaTask(9L);
        List<String> keys = List.of("users/1/shot.mp4");
        assertEquals(keys, service.burnIfNeeded(task, keys));
        verify(shotMapper, never()).selectById(any());
    }

    private static GenTask dramaTask(long shotId) {
        GenTask task = new GenTask();
        task.setParams(Map.of(
                "source", GenerateRequests.SOURCE_DRAMA,
                "shotId", shotId));
        return task;
    }
}
