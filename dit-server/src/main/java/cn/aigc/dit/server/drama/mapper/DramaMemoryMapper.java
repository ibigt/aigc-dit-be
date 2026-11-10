package cn.aigc.dit.server.drama.mapper;

import cn.aigc.dit.server.drama.entity.DramaMemory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface DramaMemoryMapper extends BaseMapper<DramaMemory> {

    @Select("SELECT * FROM drama_memory WHERE series_id = #{seriesId}")
    List<DramaMemory> listBySeries(@Param("seriesId") long seriesId);

    @Select("SELECT content FROM drama_memory WHERE series_id = #{seriesId} AND type = #{type} LIMIT 1")
    String findContent(@Param("seriesId") long seriesId, @Param("type") String type);

    @Update("""
            INSERT INTO drama_memory(series_id, type, content, source_episode_id)
            VALUES (#{seriesId}, #{type}, #{content}, #{sourceEpisodeId})
            ON CONFLICT (series_id, type)
            DO UPDATE SET content = EXCLUDED.content,
                          source_episode_id = EXCLUDED.source_episode_id,
                          updated_at = now()
            """)
    int upsert(@Param("seriesId") long seriesId,
               @Param("type") String type,
               @Param("content") String content,
               @Param("sourceEpisodeId") Long sourceEpisodeId);
}
