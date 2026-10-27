package cn.aigc.dit.generate;

/**
 * Canonical 参考素材：只记种类与对象存储 key。
 */
public class ReferenceAsset {
    private String type;
    private String storageKey;
    /** 服务端实测的素材时长（毫秒）；视频/音频参考素材计费用，图片为 null。 */
    private Long durationMs;

    public ReferenceAsset() {
    }

    public ReferenceAsset(String type, String storageKey) {
        this.type = type;
        this.storageKey = storageKey;
    }

    public ReferenceAsset(String type, String storageKey, Long durationMs) {
        this.type = type;
        this.storageKey = storageKey;
        this.durationMs = durationMs;
    }

    public String getType() {
        return type;
    }

    public ReferenceAsset setType(String type) {
        this.type = type;
        return this;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public ReferenceAsset setStorageKey(String storageKey) {
        this.storageKey = storageKey;
        return this;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public ReferenceAsset setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
        return this;
    }
}
