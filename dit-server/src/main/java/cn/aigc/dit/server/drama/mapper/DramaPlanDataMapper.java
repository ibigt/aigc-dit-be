package cn.aigc.dit.server.drama.mapper;

import cn.aigc.dit.server.drama.entity.DramaPlanData;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface DramaPlanDataMapper extends BaseMapper<DramaPlanData> {

    @Select("SELECT data FROM drama_plan_data WHERE episode_id = #{episodeId} AND key = #{key} LIMIT 1")
    String findData(@Param("episodeId") long episodeId, @Param("key") String key);

    @Select("SELECT * FROM drama_plan_data WHERE episode_id = #{episodeId} ORDER BY key")
    List<DramaPlanData> listByEpisode(@Param("episodeId") long episodeId);

    /** 用 ON CONFLICT 实现 upsert。 */
    @Update("""
            INSERT INTO drama_plan_data(episode_id, key, data, engine, error)
            VALUES (#{episodeId}, #{key}, #{data}::jsonb, #{engine}, #{error})
            ON CONFLICT (episode_id, key)
            DO UPDATE SET data = EXCLUDED.data,
                          engine = EXCLUDED.engine,
                          error = EXCLUDED.error,
                          updated_at = now()
            """)
    int upsert(@Param("episodeId") long episodeId,
               @Param("key") String key,
               @Param("data") String dataJson,
               @Param("engine") String engine,
               @Param("error") String error);
}
