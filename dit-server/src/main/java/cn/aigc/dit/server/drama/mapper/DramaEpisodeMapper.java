package cn.aigc.dit.server.drama.mapper;

import cn.aigc.dit.server.drama.entity.DramaEpisode;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;

@Mapper
public interface DramaEpisodeMapper extends BaseMapper<DramaEpisode> {

    @Update("""
            UPDATE drama_episode
               SET script_analyzing_at = #{claimedAt},
                   script_analyze_error = NULL,
                   updated_at = CURRENT_TIMESTAMP
             WHERE id = #{id}
               AND (script_analyzing_at IS NULL OR script_analyzing_at <= #{staleBefore})
            """)
    int claimScriptAnalysis(@Param("id") Long id,
                            @Param("claimedAt") OffsetDateTime claimedAt,
                            @Param("staleBefore") OffsetDateTime staleBefore);

    @Update("""
            UPDATE drama_episode
               SET script_analyzing_at = NULL,
                   script_analyze_error = NULL,
                   updated_at = CURRENT_TIMESTAMP
             WHERE id = #{id}
            """)
    int releaseScriptAnalysis(@Param("id") Long id);

    @Update("""
            UPDATE drama_episode
               SET script_analyzing_at = NULL,
                   script_analyze_error = #{error},
                   updated_at = CURRENT_TIMESTAMP
             WHERE id = #{id}
            """)
    int failScriptAnalysis(@Param("id") Long id, @Param("error") String error);

    @Update("""
            UPDATE drama_episode
               SET storyboard_drafting_at = #{claimedAt},
                   storyboard_draft_error = NULL,
                   updated_at = CURRENT_TIMESTAMP
             WHERE id = #{id}
               AND (storyboard_drafting_at IS NULL OR storyboard_drafting_at <= #{staleBefore})
            """)
    int claimStoryboardDraft(@Param("id") Long id,
                             @Param("claimedAt") OffsetDateTime claimedAt,
                             @Param("staleBefore") OffsetDateTime staleBefore);

    @Update("""
            UPDATE drama_episode
               SET storyboard_drafting_at = NULL,
                   storyboard_draft_error = NULL,
                   updated_at = CURRENT_TIMESTAMP
             WHERE id = #{id}
            """)
    int releaseStoryboardDraft(@Param("id") Long id);

    @Update("""
            UPDATE drama_episode
               SET storyboard_drafting_at = NULL,
                   storyboard_draft_error = #{error},
                   updated_at = CURRENT_TIMESTAMP
             WHERE id = #{id}
            """)
    int failStoryboardDraft(@Param("id") Long id, @Param("error") String error);
}
