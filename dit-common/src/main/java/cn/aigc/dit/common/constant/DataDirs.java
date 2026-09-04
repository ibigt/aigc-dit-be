package cn.aigc.dit.common.constant;

/**
 * Linux 服务器统一数据根目录。Postgres / Redis / MinIO（图片视频）/ 日志均落在此树下。
 */
public final class DataDirs {

    public static final String ROOT = "/mnt/ai-gc";
    public static final String POSTGRES = ROOT + "/postgres";
    public static final String REDIS = ROOT + "/redis";
    /** MinIO 对象存储数据目录，图片与视频的 storage_key 相对此桶内对象。 */
    public static final String MINIO = ROOT + "/minio";
    public static final String LOGS = ROOT + "/logs";

    private DataDirs() {
    }
}
