package cn.aigc.dit.server.drama;

import cn.aigc.dit.server.drama.entity.DramaSeries;
import cn.aigc.dit.server.drama.entity.DramaShot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DramaVideoGenerateModesTest {

    @Test
    void storedEmptyOrInheritIsR2v() {
        assertEquals(DramaVideoGenerateModes.R2V, DramaVideoGenerateModes.stored(null));
        assertEquals(DramaVideoGenerateModes.R2V, DramaVideoGenerateModes.stored(""));
        assertEquals(DramaVideoGenerateModes.R2V, DramaVideoGenerateModes.stored("inherit"));
        assertEquals(DramaVideoGenerateModes.I2V, DramaVideoGenerateModes.stored("i2v"));
        assertEquals(DramaVideoGenerateModes.R2V, DramaVideoGenerateModes.canonical("inherit"));
    }

    @Test
    void effectiveModeUsesShotThenProductDefaultNotSeries() {
        DramaSeries series = new DramaSeries();
        series.setVideoGenerateMode(DramaVideoGenerateModes.I2V);
        DramaShot shot = new DramaShot();
        assertEquals(DramaVideoGenerateModes.R2V, DramaVideoGenerateModes.effectiveMode(series, shot));

        shot.setVideoGenerateMode(DramaVideoGenerateModes.I2V);
        assertEquals(DramaVideoGenerateModes.I2V, DramaVideoGenerateModes.effectiveMode(series, shot));
        assertFalse(DramaVideoGenerateModes.inheritsMode(shot));
    }

    @Test
    void effectiveChainDefaultsOffAndIgnoresSeries() {
        DramaSeries series = new DramaSeries();
        series.setVideoGenerateMode(DramaVideoGenerateModes.R2V);
        series.setVideoChainPrevTail(true);
        DramaShot shot = new DramaShot();
        assertFalse(DramaVideoGenerateModes.effectiveChainPrevTail(series, shot));

        shot.setVideoChainPrevTail(true);
        assertTrue(DramaVideoGenerateModes.effectiveChainPrevTail(series, shot));

        shot.setVideoChainPrevTail(false);
        assertFalse(DramaVideoGenerateModes.effectiveChainPrevTail(series, shot));

        shot.setVideoGenerateMode(DramaVideoGenerateModes.I2V);
        shot.setVideoChainPrevTail(true);
        assertFalse(DramaVideoGenerateModes.effectiveChainPrevTail(series, shot));
    }
}
