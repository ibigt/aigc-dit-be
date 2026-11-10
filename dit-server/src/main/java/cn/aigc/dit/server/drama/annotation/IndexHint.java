package cn.aigc.dit.server.drama.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 编译期提示：实体类的索引 DDL 由 Flyway 迁移管理，未在实体上用
 * {@code @TableIndex} 声明（MP-Plus 3.5.7 尚未支持，3.5.9+ 才引入）。
 *
 * <p>用法：把本注解放在实体类上，{@link #reason()} 写明 Flyway 迁移文件名，
 * 既给后人留线索，又不阻塞当前 MP 版本编译。
 *
 * <p>当后续升级到 MP-Plus 3.5.9+ 后，可批量替换为：
 * <pre>{@code
 * @TableName(value = "agent_run", indexes = {
 *     @TableIndex(name = "idx_agent_run_episode", value = "episode_id"),
 *     ...
 * })
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface IndexHint {
    /** 真正创建索引的 Flyway 迁移文件名（如 V36__agent_run.sql）。 */
    String migration() default "";

    /** 索引由 Flyway 管理 / MP 暂不支持 / 其他原因。 */
    String reason();
}
