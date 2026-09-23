package cn.aigc.dit.server.mapper;

import cn.aigc.dit.server.entity.SysUser;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("SELECT status FROM sys_user WHERE id = #{id}")
    String selectStatusById(@Param("id") Long id);

    @Update("""
            UPDATE sys_user
            SET credit_balance = credit_balance + #{delta}, updated_at = NOW()
            WHERE id = #{userId} AND credit_balance + #{delta} >= 0
            """)
    int addBalance(@Param("userId") Long userId, @Param("delta") long delta);
}
