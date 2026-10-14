package cn.aigc.dit.generate;

import cn.aigc.dit.common.constant.ModelCategory;

import java.util.Locale;

/**
 * 视频模型的参考音频能力。统一供管理端展示、上传入口和生成前校验使用，
 * 避免前端显示可上传而 Provider 实际拒绝。
 */
public record AudioReferenceCapabilities(boolean supported, int maxReferences, boolean audioOnly) {

    private static final AudioReferenceCapabilities NONE = new AudioReferenceCapabilities(false, 0, false);

    public static AudioReferenceCapabilities resolve(
            String adapterKey, String provider, String category, String modelKey) {
        if (!ModelCategory.VIDEO.equalsIgnoreCase(category)) {
            return NONE;
        }
        String model = modelKey == null ? "" : modelKey.trim().toLowerCase(Locale.ROOT);
        // Seedance 1.5/1.0 即使历史数据误选了 2.x 适配器，也不能开放参考音频。
        if (isSeedance15Or10(model)) {
            return NONE;
        }
        String adapter = AdapterCatalog.resolveKeyForGenerate(adapterKey, provider, category, modelKey);
        if (AdapterCatalog.MINIMAX_H3_SGLANG.equals(adapter)) {
            return new AudioReferenceCapabilities(true, 3, true);
        }
        if (AdapterCatalog.SEEDANCE_2_5.equals(adapter)) {
            return new AudioReferenceCapabilities(true, 10, true);
        }
        if (AdapterCatalog.WAN3.equals(adapter)) {
            return new AudioReferenceCapabilities(true, 5, false);
        }
        if (AdapterCatalog.SEEDANCE_2_0.equals(adapter)) {
            return new AudioReferenceCapabilities(true, 3, false);
        }
        // 保留现有 MiniMax/Agnes 音色引用能力；两者都需要图片或视频驱动画面。
        if (AdapterCatalog.MINIMAX_H3.equals(adapter)) {
            return new AudioReferenceCapabilities(true, 3, false);
        }
        if (AdapterCatalog.AGNES_VIDEO_2_5.equals(adapter)) {
            return new AudioReferenceCapabilities(true, 5, false);
        }
        return NONE;
    }

    private static boolean isSeedance15Or10(String model) {
        return model.contains("seedance-1-5") || model.contains("seedance-1.5")
                || model.contains("seedance-1-0") || model.contains("seedance-1.0");
    }
}
