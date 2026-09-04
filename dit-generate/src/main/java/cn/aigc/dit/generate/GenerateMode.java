package cn.aigc.dit.generate;

import cn.aigc.dit.common.constant.ModelCategory;

import java.util.List;

/**
 * 内部生成模式。由参考素材 / 首尾帧推导，前端不传厂商接口名。
 */
public final class GenerateMode {
    public static final String T2I = "t2i";
    public static final String T2V = "t2v";
    public static final String I2I = "i2i";
    public static final String I2V = "i2v";
    public static final String FRAMES = "frames";

    private GenerateMode() {
    }

    public static String derive(String category, List<ReferenceAsset> references, FramePair frames) {
        if (frames != null && frames.hasAny()) {
            return FRAMES;
        }
        boolean hasRefs = references != null && !references.isEmpty();
        if (ModelCategory.IMAGE.equalsIgnoreCase(category)) {
            return hasRefs ? I2I : T2I;
        }
        return hasRefs ? I2V : T2V;
    }

    public static boolean isVideo(String mode) {
        return T2V.equals(mode) || I2V.equals(mode) || FRAMES.equals(mode);
    }
}
