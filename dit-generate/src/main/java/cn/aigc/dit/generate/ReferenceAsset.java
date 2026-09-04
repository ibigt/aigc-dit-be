package cn.aigc.dit.generate;

/**
 * Canonical 参考素材：只记种类与对象存储 key。
 */
public class ReferenceAsset {
    private String type;
    private String storageKey;

    public ReferenceAsset() {
    }

    public ReferenceAsset(String type, String storageKey) {
        this.type = type;
        this.storageKey = storageKey;
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
}
