package cn.aigc.dit.server.config;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class FlywayConfigDramaTablesTest {

    @Test
    void ensureDramaTablesCreatesAssetChapterShotAndModelColumns() throws SQLException {
        Statement statement = mock(Statement.class);
        List<String> sql = new ArrayList<>();
        doAnswer((invocation) -> {
            sql.add(invocation.getArgument(0));
            return false;
        }).when(statement).execute(anyString());

        FlywayConfig.ensureDramaTables(statement);

        String joined = String.join("\n", sql);
        assertTrue(joined.contains("CREATE TABLE IF NOT EXISTS drama_asset"), "应幂等建 drama_asset");
        assertTrue(joined.contains("CREATE TABLE IF NOT EXISTS drama_chapter"), "应幂等建 drama_chapter");
        assertTrue(joined.contains("CREATE TABLE IF NOT EXISTS drama_shot"), "应幂等建 drama_shot");
        assertTrue(joined.contains("ADD COLUMN IF NOT EXISTS image_model_id"), "应补 image_model_id");
        assertTrue(joined.contains("ADD COLUMN IF NOT EXISTS video_model_id"), "应补 video_model_id");
        assertTrue(joined.contains("ADD COLUMN IF NOT EXISTS video_poster_key"), "应补 video_poster_key");
        assertTrue(joined.contains("ADD COLUMN IF NOT EXISTS prompt_override"), "应补 prompt_override");
        assertTrue(joined.contains("ADD COLUMN IF NOT EXISTS storyboard_drafting_at"), "应补 storyboard_drafting_at");
        assertTrue(joined.contains("ADD COLUMN IF NOT EXISTS script_analyzing_at"), "应补 script_analyzing_at");
        assertTrue(joined.contains("ADD COLUMN IF NOT EXISTS script_analyze_error"), "应补 script_analyze_error");
        assertTrue(joined.contains("ADD COLUMN IF NOT EXISTS storyboard_draft_error"), "应补 storyboard_draft_error");
        assertTrue(joined.contains("真人实拍写真摄影"), "应将真人写实风前缀改为写真实拍");
        assertTrue(joined.contains("ADD COLUMN IF NOT EXISTS video_generate_mode"), "应补 video_generate_mode");
        assertTrue(joined.contains("ADD COLUMN IF NOT EXISTS video_chain_prev_tail"), "应补 video_chain_prev_tail");
        assertTrue(joined.contains("ADD COLUMN IF NOT EXISTS video_tail_key"), "应补 video_tail_key");
        assertTrue(joined.contains("ADD COLUMN IF NOT EXISTS first_frame_key"), "应补 first_frame_key");
        assertTrue(joined.contains("ADD COLUMN IF NOT EXISTS first_frame_origin"), "应补 first_frame_origin");
        assertTrue(joined.contains("COMMENT ON COLUMN drama_shot.video_generate_mode"), "应补本镜 video_generate_mode");
        assertTrue(joined.contains("COMMENT ON COLUMN drama_shot.video_chain_prev_tail"), "应补本镜 video_chain_prev_tail");
        assertTrue(joined.contains("ck_drama_shot_video_mode"), "应补本镜生成方式约束");
        assertTrue(joined.contains("ALTER COLUMN video_generate_mode SET DEFAULT 'i2v'"), "新项目应默认连续分镜");
        assertFalse(joined.contains("UPDATE drama_shot AS s"), "单镜空值应保留为继承系列设置");
    }
}
