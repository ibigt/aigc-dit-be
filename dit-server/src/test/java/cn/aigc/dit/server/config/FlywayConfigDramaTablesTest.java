package cn.aigc.dit.server.config;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
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
    }
}
