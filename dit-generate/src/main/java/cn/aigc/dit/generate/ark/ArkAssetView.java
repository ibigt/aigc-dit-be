package cn.aigc.dit.generate.ark;

/**
 * GetAsset 的状态。只有 {@code Active} 能用于推理；失败时保留方舟错误原文。
 */
public record ArkAssetView(String id, String status, String errorCode, String errorMessage) {

    public boolean active() {
        return "Active".equalsIgnoreCase(status);
    }

    public boolean failed() {
        return "Failed".equalsIgnoreCase(status);
    }

    public boolean processing() {
        return "Processing".equalsIgnoreCase(status);
    }

    public String errorText() {
        String text = ArkAssetErrors.original(errorCode, errorMessage);
        return text == null || text.isBlank() ? "素材入库失败" : text;
    }

    public String canonicalStatus() {
        if (active()) {
            return "Active";
        }
        if (failed()) {
            return "Failed";
        }
        if (processing()) {
            return "Processing";
        }
        return status == null ? "" : status.trim();
    }
}
