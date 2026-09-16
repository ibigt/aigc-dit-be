package cn.aigc.dit.server.drama;

import cn.aigc.dit.generate.caption.CaptionCue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DramaShotCaptionsTest {

    @Test
    void emptyDialogueYieldsNoCues() {
        assertTrue(DramaShotCaptions.cues(null, 8).isEmpty());
        assertTrue(DramaShotCaptions.cues("局长：  ", 8).isEmpty());
        assertTrue(DramaShotCaptions.cues("洛白：走。", 0).isEmpty());
    }

    @Test
    void singleLineCoversWholeShot() {
        List<CaptionCue> cues = DramaShotCaptions.cues("局长：洛老弟，别睡了。", 5);
        assertEquals(1, cues.size());
        assertEquals(0.0, cues.get(0).startSec());
        assertEquals(5.0, cues.get(0).endSec());
        assertEquals("洛老弟，别睡了。", cues.get(0).text());
    }

    @Test
    void multipleLinesSplitDurationByTextLength() {
        List<CaptionCue> cues = DramaShotCaptions.cues("旁白：风越来越大。\n洛白：走吧。", 7);
        assertEquals(2, cues.size());
        assertEquals(0.0, cues.get(0).startSec());
        assertEquals(5.0, cues.get(0).endSec(), 0.001);
        assertEquals("风越来越大。", cues.get(0).text());
        assertEquals(5.0, cues.get(1).startSec(), 0.001);
        assertEquals(7.0, cues.get(1).endSec());
        assertEquals("走吧。", cues.get(1).text());
    }
}
