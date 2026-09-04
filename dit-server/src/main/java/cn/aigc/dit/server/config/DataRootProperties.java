package cn.aigc.dit.server.config;

import cn.aigc.dit.common.constant.DataDirs;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 落盘路径，默认在 {@link DataDirs#ROOT}，由 {@code application.yml} 的 {@code dit.storage.*} 配置。
 */
@Data
@ConfigurationProperties(prefix = "dit.storage")
public class DataRootProperties {

    /** 统一数据根，默认 {@code /mnt/ai-gc}。 */
    private String root = DataDirs.ROOT;
    private String postgres = DataDirs.POSTGRES;
    private String redis = DataDirs.REDIS;
    private String minio = DataDirs.MINIO;
    private String logs = DataDirs.LOGS;
}
