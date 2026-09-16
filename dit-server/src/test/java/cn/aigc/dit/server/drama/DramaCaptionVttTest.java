package cn.aigc.dit.server.drama;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DramaCaptionVttTest {

    @Test
    void shotVttUsesOnlyCurrentShotDialogue() {
        String vtt = DramaCaptionVtt.shot("洛白：这是当前镜头。", 5.4);

        assertTrue(vtt.startsWith("WEBVTT\n\n"));
        assertTrue(vtt.contains("00:00:00.000 --> 00:00:05.400"));
        assertTrue(vtt.contains("这是当前镜头。"));
        assertFalse(vtt.contains("洛白："));
    }

    @Test
    void episodeVttOffsetsFollowingShotByActualDuration() {
        String vtt = DramaCaptionVtt.render(List.of(
                new DramaCaptionVtt.Shot("旁白：第一镜。", 5.4),
                new DramaCaptionVtt.Shot("夏安：第二镜。", 3.2)));

        assertTrue(vtt.contains("00:00:00.000 --> 00:00:05.400"));
        assertTrue(vtt.contains("00:00:05.400 --> 00:00:08.600"));
    }
}
