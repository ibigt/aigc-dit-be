package cn.aigc.dit.server.config;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowRunHistoryMigrationTest {

    @Test
    void v32RepairsSkippedWorkflowMigrationsAfterVersion31() throws Exception {
        String sql;
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("db/migration/V55__workflow_run_history.sql")) {
            assertNotNull(in, "V54 must run after databases already at V31");
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        int workflowTable = sql.indexOf("CREATE TABLE IF NOT EXISTS workflow (");
        int runTable = sql.indexOf("CREATE TABLE IF NOT EXISTS workflow_run (");
        int historyColumn = sql.indexOf("ADD COLUMN IF NOT EXISTS previous_run_id BIGINT");
        assertTrue(workflowTable >= 0 && workflowTable < runTable && runTable < historyColumn,
                "V54 must create both skipped V29 tables before applying V30 history changes");
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("db/migration/V53__workflow.sql")) {
            assertNotNull(in);
            String v29 = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String v29Schema = v29.substring(v29.indexOf("CREATE TABLE IF NOT EXISTS workflow ("),
                    v29.indexOf("INSERT INTO workflow (")).trim();
            assertTrue(sql.contains(v29Schema), "V54 must reproduce all V29 table columns, comments and indexes");
        }
        assertMatches(sql, "(?is)user_id\\s+BIGINT\\s+REFERENCES\\s+sys_user\\s*\\(id\\)");
        assertMatches(sql, "(?is)CONSTRAINT\\s+ck_workflow_type\\s+CHECK\\s*\\(type\\s+IN\\s*\\('image',\\s*'video',\\s*'audio',\\s*'import',\\s*'template'\\)\\)");
        assertMatches(sql, "(?is)workflow_id\\s+BIGINT\\s+REFERENCES\\s+workflow\\s*\\(id\\)\\s+ON\\s+DELETE\\s+SET\\s+NULL");
        assertMatches(sql, "(?is)steps\\s+JSONB\\s+NOT\\s+NULL\\s+DEFAULT\\s+'\\[\\]'::jsonb");
        assertMatches(sql, "(?is)CREATE\\s+INDEX\\s+IF\\s+NOT\\s+EXISTS\\s+idx_workflow_user_updated\\s+ON\\s+workflow\\s*\\(user_id,\\s*updated_at\\s+DESC\\)");
        assertMatches(sql, "(?is)CREATE\\s+INDEX\\s+IF\\s+NOT\\s+EXISTS\\s+idx_workflow_run_user\\s+ON\\s+workflow_run\\s*\\(user_id,\\s*created_at\\s+DESC\\)");
        assertMatches(sql, "(?is)CREATE\\s+INDEX\\s+IF\\s+NOT\\s+EXISTS\\s+idx_workflow_run_status\\s+ON\\s+workflow_run\\s*\\(status\\)");
        assertMatches(sql, "(?is)INSERT\\s+INTO\\s+workflow\\s*\\(.*?\\)\\s+SELECT\\s+.*?WHERE\\s+NOT\\s+EXISTS\\s*\\(");
        assertMatches(sql, "(?is)w\\.builtin\\s*=\\s*TRUE");
        assertMatches(sql, "(?is)w\\.demo_key\\s*=\\s*s\\.demo_key");
        assertMatches(sql, "(?is)w\\.source_path\\s*=\\s*s\\.source_path");
        for (String identity : new String[] {"image-to-video", "text-to-video", "shot-variations", "image-board",
                "/comfyui-workflows/minimax-h3/video_minimax_h3_i2v.json"}) {
            assertTrue(sql.contains("'" + identity + "'"), "Missing builtin workflow seed: " + identity);
        }

        assertMatches(sql, "(?is)ALTER\\s+TABLE\\s+workflow_run\\s+ADD\\s+COLUMN\\s+IF\\s+NOT\\s+EXISTS\\s+previous_run_id\\s+BIGINT\\b");
        assertMatches(sql, "(?is)DO\\s+\\$\\$.*IF\\s+NOT\\s+EXISTS\\s*\\(\\s*SELECT.*FROM\\s+pg_constraint.*END\\s+IF;.*END\\s*\\$\\$");
        assertMatches(sql, "(?is)conrelid\\s*=\\s*'workflow_run'::regclass");
        assertMatches(sql, "(?is)confrelid\\s*=\\s*'workflow_run'::regclass");
        assertMatches(sql, "(?is)contype\\s*=\\s*'f'");
        assertMatches(sql, "(?is)attname\\s*=\\s*'previous_run_id'");
        assertMatches(sql, "(?is)attname\\s*=\\s*'id'");
        assertMatches(sql, "(?is)conkey\\s*=\\s*ARRAY\\[.*attnum\\s*\\]");
        assertMatches(sql, "(?is)confkey\\s*=\\s*ARRAY\\[.*attnum\\s*\\]");
        assertMatches(sql, "(?is)confdeltype\\s*=\\s*'n'");
        assertMatches(sql, "(?is)ALTER\\s+TABLE\\s+workflow_run\\s+ADD\\s+CONSTRAINT\\s+\\w+\\s+FOREIGN\\s+KEY\\s*\\(previous_run_id\\)\\s+REFERENCES\\s+workflow_run\\s*\\(id\\)\\s+ON\\s+DELETE\\s+SET\\s+NULL");
        assertMatches(sql, "(?is)CREATE\\s+INDEX\\s+IF\\s+NOT\\s+EXISTS\\s+idx_workflow_run_owner_workflow_id\\s+ON\\s+workflow_run\\s*\\(user_id\\s*,\\s*workflow_id\\s*,\\s*id\\s+DESC\\)");
    }

    private static void assertMatches(String sql, String regex) {
        assertTrue(Pattern.compile(regex).matcher(sql).find(), "Migration must match: " + regex);
    }
}
