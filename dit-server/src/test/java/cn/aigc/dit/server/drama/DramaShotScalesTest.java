package cn.aigc.dit.server.drama;

import cn.aigc.dit.server.drama.entity.DramaShot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DramaShotScalesTest {

    @Test
    void classifiesShotSizeFromActionPrefix() {
        assertEquals(DramaShotScales.Bucket.CLOSE, DramaShotScales.ofText("特写。洛白拿怀表。"));
        assertEquals(DramaShotScales.Bucket.WIDE, DramaShotScales.ofText("全景。风扇转动。"));
        assertEquals(DramaShotScales.Bucket.MEDIUM, DramaShotScales.ofText("中景。局长推门。"));
        assertEquals(DramaShotScales.Bucket.MEDIUM, DramaShotScales.ofText("交代空间"));
        assertEquals(DramaShotScales.Bucket.MEDIUM, DramaShotScales.ofText("中景。对话。", "怀表特写"));
        DramaShot mediumTitledClose = shot(90L, "交代空间");
        mediumTitledClose.setTitle("怀表特写");
        assertEquals(DramaShotScales.Bucket.MEDIUM, DramaShotScales.of(mediumTitledClose));
    }

    @Test
    void closeUpDoesNotAutoChainFromWide() {
        DramaShot wide = shot(90L, "全景。办公室。");
        DramaShot close = shot(90L, "特写。洛白手中古铜怀表。");
        DramaShot otherChapter = shot(91L, "中景。对话。");
        assertTrue(DramaShotScales.sameChapter(wide, close));
        assertFalse(DramaShotScales.scaleCompatible(wide, close));
        assertFalse(DramaShotScales.chainable(wide, close));
        assertTrue(DramaShotScales.chainable(wide, shot(90L, "中景。局长说话。")));
        assertFalse(DramaShotScales.chainable(wide, otherChapter));
        assertTrue(DramaShotScales.chainable(close, shot(90L, "近景。怀表反光。")));
    }

    @Test
    void incomingCastDoesNotAutoChain() {
        DramaShot prev = shot(90L, "全景。洛白用书盖脸。");
        prev.setCharacterNames(List.of("洛白", "夏安"));
        DramaShot incoming = shot(90L, "中景。局长推门将档案摔桌，洛白起身。");
        incoming.setCharacterNames(List.of("局长", "洛白"));
        DramaShot sameCast = shot(90L, "中景。洛白起身。");
        sameCast.setCharacterNames(List.of("洛白"));
        assertTrue(DramaShotScales.sameChapter(prev, incoming));
        assertTrue(DramaShotScales.scaleCompatible(prev, incoming));
        assertFalse(DramaShotScales.castCompatible(prev, incoming));
        assertFalse(DramaShotScales.chainable(prev, incoming));
        assertTrue(DramaShotScales.chainable(prev, sameCast));
    }

    private static DramaShot shot(Long chapterId, String action) {
        DramaShot shot = new DramaShot();
        shot.setChapterId(chapterId);
        shot.setNarrativeGoal(action);
        return shot;
    }
}
