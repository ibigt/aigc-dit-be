package cn.aigc.dit.server.drama.mapper;

import cn.aigc.dit.server.drama.entity.DramaShot;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DramaShotMapper extends BaseMapper<DramaShot> {

    /** 锁住这一镜直到当前事务结束，挡住并发的第二次出片。 */
    @Select("SELECT id FROM drama_shot WHERE id = #{id} FOR UPDATE")
    Long lockById(@Param("id") Long id);
}
