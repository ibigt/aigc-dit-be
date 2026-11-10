package cn.aigc.dit.server.drama;

import cn.aigc.dit.server.drama.entity.DramaEpisode;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 剧集行的字段更新策略回归测试。
 *
 * <p>2026-11-03 线上故障：分析锁列（{@code script_analyzing_at} 等）带着
 * {@link FieldStrategy#ALWAYS}，一键分析期间任何一次稀疏 {@code updateById}
 * （落原文、落剧本）都会把没赋值的锁列写成 NULL —— 锁刚占上就被清掉，
 * 页面进度自己消失、用户能再点一次分析、两次分析写同一集，
 * 甚至分析还在跑时项目被删掉。
 *
 * <p>锁与错误列只能由 {@code DramaEpisodeMapper} 的专用 SQL 读写，因此这里固定住
 * 「锁列不是 ALWAYS」；成片列相反，必须保持 ALWAYS，否则清空成片时列不会被写成 NULL。
 */
class DramaEpisodeUpdateStrategyTest {

    /** 分析锁与错误列：不允许被稀疏更新带着写。 */
    private static final String[] LOCK_COLUMNS = {
            "storyboardDraftingAt",
            "scriptAnalyzingAt",
            "scriptAnalyzingHeartbeatAt",
            "scriptAnalyzeError",
            "storyboardDraftError",
    };

    /** 成片列：清空成片时要能把列写成 NULL。 */
    private static final String[] FILM_COLUMNS = {
            "filmKey",
            "filmPosterKey",
            "filmFingerprint",
            "filmAssembledAt",
            "filmShotIds",
    };

    @Test
    void lockColumnsAreNotAlwaysUpdated() throws NoSuchFieldException {
        for (String column : LOCK_COLUMNS) {
            FieldStrategy strategy = updateStrategyOf(column);
            assertNotEquals(FieldStrategy.ALWAYS, strategy,
                    column + " 不能用 ALWAYS：稀疏 updateById 会把没赋值的锁列写成 NULL");
        }
    }

    @Test
    void filmColumnsStillClearOnUpdate() throws NoSuchFieldException {
        for (String column : FILM_COLUMNS) {
            FieldStrategy strategy = updateStrategyOf(column);
            assertEquals(FieldStrategy.ALWAYS, strategy,
                    column + " 必须保持 ALWAYS：清空成片时要能把列写成 NULL");
        }
    }

    /** 没标 {@code @TableField} 的列走 MyBatis-Plus 默认策略（null 不更新）。 */
    private static FieldStrategy updateStrategyOf(String fieldName) throws NoSuchFieldException {
        Field field = DramaEpisode.class.getDeclaredField(fieldName);
        TableField tableField = field.getAnnotation(TableField.class);
        return tableField == null ? FieldStrategy.NOT_NULL : tableField.updateStrategy();
    }
}
