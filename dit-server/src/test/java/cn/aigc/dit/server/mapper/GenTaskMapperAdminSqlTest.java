package cn.aigc.dit.server.mapper;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenTaskMapperAdminSqlTest {

    @Test
    void selectAdminPageJoinsUserModelAndSupportsFilterPaging() throws Exception {
        Select select = GenTaskMapper.class.getMethod(
                "selectAdminPage",
                OffsetDateTime.class, OffsetDateTime.class, String.class, String.class, int.class, int.class)
                .getAnnotation(Select.class);
        String sql = String.join("\n", select.value());

        assertTrue(sql.contains("LEFT JOIN sys_user u ON u.id = t.user_id"), "应 join 用户展示名");
        assertTrue(sql.contains("LEFT JOIN ai_model m ON m.id = t.model_id"), "应 join 模型名称");
        assertTrue(sql.contains("COALESCE(u.display_name, u.username, '未知用户')"), "用户列走 display_name");
        assertTrue(sql.contains("COALESCE(m.name, '未知模型')"), "模型列走 ai_model.name");
        assertTrue(sql.contains("t.started_at AS startedAt"), "排队耗时需要 started_at");
        assertTrue(sql.contains("t.status = #{status}"), "支持按状态筛选");
        assertTrue(sql.contains("CONCAT('REQ-', t.id) ILIKE"), "支持请求编号搜索");
        assertTrue(sql.contains("CONCAT('TASK-', t.id) ILIKE"), "支持任务编号搜索");
        assertTrue(sql.contains("u.display_name"), "支持用户名搜索");
        assertTrue(sql.contains("m.name"), "支持模型名搜索");
        assertTrue(sql.contains("ORDER BY t.created_at DESC"), "管理端按时间倒序");
        assertTrue(sql.contains("LIMIT #{limit} OFFSET #{offset}"), "分页走 LIMIT/OFFSET");
        assertTrue(sql.contains("t.created_at"), "时间窗按 created_at");
    }

    @Test
    void countAdminPageUsesSameJoinAndFilters() throws Exception {
        Select select = GenTaskMapper.class.getMethod(
                "countAdminPage",
                OffsetDateTime.class, OffsetDateTime.class, String.class, String.class)
                .getAnnotation(Select.class);
        String sql = String.join("\n", select.value());
        assertTrue(sql.contains("SELECT COUNT(*)"));
        assertTrue(sql.contains("LEFT JOIN sys_user u ON u.id = t.user_id"));
        assertTrue(sql.contains("LEFT JOIN ai_model m ON m.id = t.model_id"));
        assertTrue(sql.contains("t.status = #{status}"));
        assertTrue(sql.contains("CONCAT('REQ-', t.id) ILIKE"));
    }

    @Test
    void cancelAndRetryUpdatesResetOpsFields() throws Exception {
        Update cancel = GenTaskMapper.class.getMethod("markCancelled", Long.class).getAnnotation(Update.class);
        String cancelSql = String.join("\n", cancel.value());
        assertTrue(cancelSql.contains("status = 'CANCELLED'"));
        assertTrue(cancelSql.contains("progress = 0"));
        assertTrue(cancelSql.contains("finished_at = NOW()"));

        Update retry = GenTaskMapper.class.getMethod("resetForRetry", Long.class).getAnnotation(Update.class);
        String retrySql = String.join("\n", retry.value());
        assertTrue(retrySql.contains("status = 'PENDING'"));
        assertTrue(retrySql.contains("started_at = NULL"));
        assertTrue(retrySql.contains("provider_job_id = NULL"));
        assertTrue(retrySql.contains("finished_at = NULL"));
        assertTrue(retrySql.contains("output_storage_key = NULL"));
    }

    @Test
    void v9AddsStartedAtAndAdminIndexes() throws Exception {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("db/migration/V9__admin_task_ops.sql")) {
            assertNotNull(in, "缺少 V9 迁移");
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("started_at TIMESTAMPTZ"));
            assertTrue(sql.contains("idx_gen_task_created"));
            assertTrue(sql.contains("idx_gen_task_status_created"));
        }
    }
}
