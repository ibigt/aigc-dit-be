package cn.aigc.dit.generate;

import cn.aigc.dit.common.constant.ModelCategory;
import cn.aigc.dit.generate.agnes.AgnesRequestMapper;
import cn.aigc.dit.generate.ark.ArkRequestMapper;
import cn.aigc.dit.generate.dashscope.DashScopeRequestMapper;
import cn.aigc.dit.generate.minimax.MiniMaxRequestMapper;

import java.util.Locale;

/**
 * 视频模型单次可携带的参考图张数。与各适配器提交前的图片上限一致，
 * 供分镜页提示和出片校验共用。
 */
public final class ImageReferenceCapabilities {

    /** 未单列适配器与 RunningHub / ComfyUI 的参考图上限对齐。 */
    public static final int DEFAULT_MAX_IMAGES = 9;

    private ImageReferenceCapabilities() {
    }

    public static int maxImages(String adapterKey, String provider, String category, String modelKey) {
        if (!ModelCategory.VIDEO.equalsIgnoreCase(category)) {
            return 0;
        }
        String adapter = AdapterCatalog.resolveKeyForGenerate(adapterKey, provider, category, modelKey);
        if (AdapterCatalog.SEEDANCE_2_5.equals(adapter)) {
            return 30;
        }
        if (AdapterCatalog.isArk(adapter)
                || AdapterCatalog.SEEDANCE_2_0.equals(adapter)
                || AdapterCatalog.SEEDANCE_2.equals(adapter)) {
            return ArkRequestMapper.MAX_IMAGES;
        }
        if (AdapterCatalog.MINIMAX_H3.equals(adapter) || AdapterCatalog.MINIMAX_H3_SGLANG.equals(adapter)) {
            return MiniMaxRequestMapper.MAX_IMAGES;
        }
        if (AdapterCatalog.AGNES_VIDEO_2_5.equals(adapter)) {
            return agnesMaxImages(modelKey);
        }
        if (AdapterCatalog.WAN3.equals(adapter) || AdapterCatalog.isDashScope(adapter)) {
            return DashScopeRequestMapper.MAX_REFERENCE_IMAGES;
        }
        return DEFAULT_MAX_IMAGES;
    }

    private static int agnesMaxImages(String modelKey) {
        String model = modelKey == null ? "" : modelKey.trim().toLowerCase(Locale.ROOT);
        if (model.isEmpty() || model.contains("flash")) {
            return AgnesRequestMapper.FLASH_MAX_IMAGES;
        }
        return Integer.MAX_VALUE;
    }
}
