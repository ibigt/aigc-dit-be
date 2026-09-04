package cn.aigc.dit.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "dit.admin")
public class AdminProperties {
    private String username = "admin";
    private String password = "Fnii@123";
    private String displayName = "系统管理员";
    private String email = "admin@localhost";
}
