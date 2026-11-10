package cn.aigc.dit.server.drama.mapper;

import cn.aigc.dit.server.drama.entity.ScriptPrompt;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ScriptPromptMapper extends BaseMapper<ScriptPrompt> {

    /** 取某 key 当前生效版本的内容。 */
    @Select("SELECT content FROM script_prompt WHERE key = #{key} AND active = true LIMIT 1")
    String findActiveContent(@Param("key") String key);

    /** 取某 key 的最新版本号（用于写入时自增）。 */
    @Select("SELECT COALESCE(MAX(version), 0) FROM script_prompt WHERE key = #{key}")
    Integer findMaxVersion(@Param("key") String key);

    /** 把同 key 其它版本全部置为非激活（写入新版本前调用）。 */
    @Update("UPDATE script_prompt SET active = false, updated_at = now() WHERE key = #{key} AND active = true")
    int deactivateByKey(@Param("key") String key);
}
