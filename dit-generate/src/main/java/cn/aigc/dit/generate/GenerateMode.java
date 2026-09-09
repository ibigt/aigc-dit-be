package cn.aigc.dit.generate;

import cn.aigc.dit.common.constant.ModelCategory;

import java.util.ArrayList;
import java.util.List;

/**
 * 内部生成模式。由参考素材 / 首尾帧推导，前端不传厂商接口名。
 * <p>
 * 视频：{@code t2v} 文生视频，{@code r2v} 参考生视频（图片或视频参考），{@code i2v} 首尾帧。
 */
public final class GenerateMode {
    public static final String T2I = "t2i";
    public static final String T2V = "t2v";
    public static final String I2I = "i2i";
    public static final String R2V = "r2v";
    public static final String I2V = "i2v";

    /** @deprecated 旧称，读入时规范为 {@link #I2V} */
    public static final String V2V = "v2v";
    /** @deprecated 旧称，读入时规范为 {@link #I2V} */
    public static final String FRAMES = "frames";

    private GenerateMode() {
    }

    public static String derive(String category, List<ReferenceAsset> references, FramePair frames) {
        if (frames != null && frames.hasAny()) {
            return I2V;
        }
        boolean hasRefs = references != null && !references.isEmpty();
        if (ModelCategory.IMAGE.equalsIgnoreCase(category)) {
            return hasRefs ? I2I : T2I;
        }
        return hasRefs ? R2V : T2V;
    }

    public static String canonical(String mode) {
        if (mode == null || mode.isBlank()) {
            return mode;
        }
        String value = mode.trim();
        if (FRAMES.equalsIgnoreCase(value) || "frame".equalsIgnoreCase(value) || V2V.equalsIgnoreCase(value)) {
            return I2V;
        }
        return value;
    }

    public static boolean isSpecificMode(String cap) {
        if (cap == null || cap.isBlank()) {
            return false;
        }
        String value = canonical(cap);
        return T2I.equalsIgnoreCase(value)
                || I2I.equalsIgnoreCase(value)
                || T2V.equalsIgnoreCase(value)
                || R2V.equalsIgnoreCase(value)
                || I2V.equalsIgnoreCase(value);
    }

    public static List<String> canonicalizeCapabilities(List<String> capabilities) {
        if (capabilities == null) {
            return null;
        }
        List<String> values = new ArrayList<>();
        for (String item : capabilities) {
            if (item == null || item.isBlank()) {
                continue;
            }
            String cap = canonical(item.trim());
            boolean exists = false;
            for (String seen : values) {
                if (seen.equalsIgnoreCase(cap)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                values.add(cap);
            }
        }
        return values;
    }

    public static boolean isVideo(String mode) {
        String value = canonical(mode);
        return T2V.equalsIgnoreCase(value) || R2V.equalsIgnoreCase(value) || I2V.equalsIgnoreCase(value);
    }
}
