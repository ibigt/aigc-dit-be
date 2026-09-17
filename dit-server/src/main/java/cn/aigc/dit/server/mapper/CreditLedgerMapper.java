package cn.aigc.dit.server.mapper;

import cn.aigc.dit.server.entity.CreditLedger;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CreditLedgerMapper extends BaseMapper<CreditLedger> {

    @Select("""
            SELECT COALESCE(SUM(change_amt), 0) FROM credit_ledger
            WHERE ref_id = #{refId}
            """)
    long sumByRefId(@Param("refId") String refId);
}
