package cn.aigc.dit.server.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 启动时读取 classpath 上的 version 文件。缺失或空白只记 warn，不阻断启动。
 */
@Component
public class AppVersion {

    private static final Logger log = LoggerFactory.getLogger(AppVersion.class);

    private final String version;

    public AppVersion() {
        this.version = read(new ClassPathResource("version"));
    }

    public String getVersion() {
        return version;
    }

    static String read(Resource resource) {
        if (!resource.exists()) {
            log.warn("classpath version 文件缺失，版本号记为空");
            return "";
        }
        try (InputStream input = resource.getInputStream()) {
            String text = StreamUtils.copyToString(input, StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) {
                log.warn("classpath version 文件为空白，版本号记为空");
                return "";
            }
            return text;
        } catch (IOException ex) {
            log.warn("读取 classpath version 失败，版本号记为空", ex);
            return "";
        }
    }
}
