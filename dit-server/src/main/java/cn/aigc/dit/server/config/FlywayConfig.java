package cn.aigc.dit.server.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * V1 已执行过的库不会重跑脚本。启动时幂等补 adapter_key，避免 checksum 变更导致无法启动。
 */
@Configuration
public class FlywayConfig {

    @Bean
    public FlywayMigrationStrategy adapterKeyPatch() {
        return (Flyway flyway) -> {
            flyway.migrate();
            try (Connection connection = flyway.getConfiguration().getDataSource().getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE ai_model ADD COLUMN IF NOT EXISTS adapter_key VARCHAR(64)");
                statement.execute("""
                        COMMENT ON COLUMN ai_model.adapter_key
                        IS '适配协议（出站翻译包）：gpt-image-2 / seedance-2 / minimax-h3 / agnes-image-2.5 / agnes-video-2.5；mock 为占位'
                        """);
                statement.execute("""
                        UPDATE ai_model
                        SET adapter_key = 'minimax-h3', updated_at = NOW()
                        WHERE (adapter_key IS NULL OR adapter_key = '')
                          AND category = 'video'
                          AND (provider ILIKE '%comfy%' OR model_key ILIKE '%minimax%')
                        """);
                statement.execute("""
                        UPDATE ai_model
                        SET adapter_key = 'seedance-2', updated_at = NOW()
                        WHERE (adapter_key IS NULL OR adapter_key = '')
                          AND category = 'video'
                          AND (provider ILIKE '%running%' OR model_key ILIKE '%seedance%')
                        """);
                statement.execute("""
                        UPDATE ai_model
                        SET adapter_key = 'gpt-image-2', updated_at = NOW()
                        WHERE (adapter_key IS NULL OR adapter_key = '')
                          AND category = 'image'
                          AND (provider ILIKE '%running%' OR model_key ILIKE '%gpt-image%')
                        """);
                statement.execute("""
                        UPDATE ai_model
                        SET adapter_key = 'mock', updated_at = NOW()
                        WHERE (adapter_key IS NULL OR adapter_key = '')
                          AND (provider ILIKE 'mock' OR model_key ILIKE 'mock%')
                        """);
                statement.execute("""
                        UPDATE ai_model SET provider = 'ComfyUI', updated_at = NOW()
                        WHERE adapter_key = 'minimax-h3' AND provider IS DISTINCT FROM 'ComfyUI'
                        """);
                statement.execute("""
                        UPDATE ai_model SET provider = 'RunningHub', updated_at = NOW()
                        WHERE adapter_key IN ('gpt-image-2', 'seedance-2') AND provider IS DISTINCT FROM 'RunningHub'
                        """);
                statement.execute("""
                        UPDATE ai_model SET provider = '官方API', updated_at = NOW()
                        WHERE adapter_key IN ('agnes-image-2.5', 'agnes-video-2.5') AND provider IS DISTINCT FROM '官方API'
                        """);
                statement.execute("""
                        UPDATE ai_model SET provider = 'mock', updated_at = NOW()
                        WHERE adapter_key = 'mock' AND provider IS DISTINCT FROM 'mock'
                        """);
            } catch (SQLException e) {
                throw new IllegalStateException("补齐 ai_model.adapter_key 失败", e);
            }
        };
    }
}
