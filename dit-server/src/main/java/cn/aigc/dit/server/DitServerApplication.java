package cn.aigc.dit.server;

import cn.aigc.dit.server.config.AdminProperties;
import cn.aigc.dit.server.config.CryptoProperties;
import cn.aigc.dit.server.config.DataRootProperties;
import cn.aigc.dit.server.security.JwtProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "cn.aigc.dit")
@MapperScan({"cn.aigc.dit.server.mapper", "cn.aigc.dit.server.drama.mapper"})
@EnableConfigurationProperties({JwtProperties.class, AdminProperties.class, DataRootProperties.class, CryptoProperties.class})
@EnableScheduling
public class DitServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DitServerApplication.class, args);
    }
}
