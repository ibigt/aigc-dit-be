package cn.aigc.dit.server.drama;

import cn.aigc.dit.generate.GenerateMode;
import cn.aigc.dit.server.drama.entity.DramaSeries;
import cn.aigc.dit.server.drama.entity.DramaShot;
import org.springframework.util.StringUtils;

/**
 * 漫剧出片方式：参考图 {@code r2v} / 首尾帧 {@code i2v}。
 * 单镜可覆盖系列设置；空值或 {@code inherit} 表示跟随系列。
 */
public final class DramaVideoGenerateModes {

    public static final String R2V = GenerateMode.R2V;
    public static final String I2V = GenerateMode.I2V;
    public static final String INHERIT = "inherit";

    private DramaVideoGenerateModes() {
    }

    public static String canonical(String mode) {
        String value = GenerateMode.canonical(mode);
        if (I2V.equalsIgnoreCase(value)) {
            return I2V;
        }
        return R2V;
    }

    public static boolean isInherit(String mode) {
        return !StringUtils.hasText(mode) || INHERIT.equalsIgnoreCase(mode.trim());
    }

    /** 写入 drama_shot.video_generate_mode：null 表示跟随系列。 */
    public static String stored(String mode) {
        return isInherit(mode) ? null : canonical(mode);
    }

    public static void applyProductDefault(DramaShot shot) {
        if (shot == null) {
            return;
        }
        shot.setVideoGenerateMode(null);
        shot.setVideoChainPrevTail(null);
    }

    public static boolean inheritsMode(DramaShot shot) {
        return shot == null || !StringUtils.hasText(shot.getVideoGenerateMode());
    }

    public static boolean inheritsChain(DramaShot shot) {
        return shot == null || shot.getVideoChainPrevTail() == null;
    }

    public static String effectiveMode(DramaSeries series, DramaShot shot) {
        if (shot != null && StringUtils.hasText(shot.getVideoGenerateMode())) {
            return canonical(shot.getVideoGenerateMode());
        }
        if (series != null && StringUtils.hasText(series.getVideoGenerateMode())) {
            return canonical(series.getVideoGenerateMode());
        }
        return R2V;
    }

    public static boolean effectiveChainPrevTail(DramaSeries series, DramaShot shot) {
        if (isI2v(effectiveMode(series, shot))) {
            return false;
        }
        if (shot != null && shot.getVideoChainPrevTail() != null) {
            return Boolean.TRUE.equals(shot.getVideoChainPrevTail());
        }
        return series != null && Boolean.TRUE.equals(series.getVideoChainPrevTail());
    }

    public static boolean isI2v(String mode) {
        return I2V.equals(canonical(mode));
    }

    public static boolean isR2v(String mode) {
        return R2V.equals(canonical(mode));
    }
}
