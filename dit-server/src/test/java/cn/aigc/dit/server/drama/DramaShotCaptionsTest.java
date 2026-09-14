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
    void multipleLinesSplitDurationEvenly() {
        List<CaptionCue> cues = DramaShotCaptions.cues("旁白：风很大。\n洛白：走吧。", 6);
        assertEquals(2, cues.size());
        assertEquals(0.0, cues.get(0).startSec());
        assertEquals(3.0, cues.get(0).endSec());
        assertEquals("风很大。", cues.get(0).text());
        assertEquals(3.0, cues.get(1).startSec());
        assertEquals(6.0, cues.get(1).endSec());
        assertEquals("走吧。", cues.get(1).text());
    }
}
