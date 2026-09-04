package cn.aigc.dit.server.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        SecurityScheme bearer = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT");
        return new OpenAPI()
                .info(new Info()
                        .title("致启·光驭 API")
                        .description("一期：认证、用户/模型管理、仪表盘、快速创作 Mock 闭环")
                        .version("0.1.0"))
                .components(new Components().addSecuritySchemes("Bearer", bearer))
                .addSecurityItem(new SecurityRequirement().addList("Bearer"));
    }
}
