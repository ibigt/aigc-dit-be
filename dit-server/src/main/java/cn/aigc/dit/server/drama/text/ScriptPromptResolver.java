package cn.aigc.dit.server.drama.text;

import cn.aigc.dit.server.drama.mapper.ScriptPromptMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Prompt 解析器：从 script_prompt 表读取指定 key 的当前生效版本，支持本地 Caffeine 缓存。
 *
 * 用法：
 *   String system = promptResolver.resolve("common") + "\n\n" + promptResolver.resolve("schema_script");
 *
 * 缓存策略：5 分钟 TTL，写入新版本时通过 invalidate(key) 立即失效。
 */
@Slf4j
@Component
public class ScriptPromptResolver implements InitializingBean {

    private final ScriptPromptMapper mapper;
    private final Cache<String, String> cache;

    public ScriptPromptResolver(ScriptPromptMapper mapper) {
        this.mapper = mapper;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(5))
                .maximumSize(256)
                .build();
    }

    /**
     * 取指定 key 的当前生效 Prompt 内容。
     * 缓存命中即返回；未命中读 DB 并缓存。
     * DB 也未命中时回退到 Java 常量（DramaTextPrompts 暴露的 fallback）。
     */
    public String resolve(String key) {
        return cache.get(key, this::loadFromDbOrFallback);
    }

    /** 显式失效缓存（管理端写入新版本后调用）。 */
    public void invalidate(String key) {
        cache.invalidate(key);
    }

    /** 全量失效（管理端批量刷新时调用）。 */
    public void invalidateAll() {
        cache.invalidateAll();
    }

    @Override
    public void afterPropertiesSet() {
        // 启动时预热常用 key（避免冷启动第一次慢）
        for (String key : new String[]{"common", "schema_skeleton", "schema_script",
                "schema_semantic_normalization", "schema_assets", "schema_shots",
                "supervisor_skeleton", "supervisor_script", "supervisor_assets", "supervisor_shots"}) {
            resolve(key);
        }
        log.info("[script-prompt] 预热完成，已加载 {} 条常用 prompt", cache.estimatedSize());
    }

    private String loadFromDbOrFallback(String key) {
        try {
            String content = mapper.findActiveContent(key);
            if (content != null && !content.isBlank()) {
                return content;
            }
        } catch (Exception e) {
            log.warn("[script-prompt] DB 读取失败 key={} 走 fallback：{}", key, e.getMessage());
        }
        String fallback = DramaTextPrompts.fallback(key);
        if (fallback == null) {
            throw new IllegalStateException("prompt key not found: " + key
                    + "（DB 无记录且 Java 常量未提供 fallback）");
        }
        return fallback;
    }
}
