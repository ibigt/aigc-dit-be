package cn.aigc.dit.server.mapper;

import cn.aigc.dit.server.entity.GenTask;
import cn.aigc.dit.server.mapper.stat.HourCount;
import cn.aigc.dit.server.mapper.stat.NamedCount;
import cn.aigc.dit.server.mapper.stat.UserTaskStat;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface GenTaskMapper extends BaseMapper<GenTask> {

    @Select({
            "<script>",
            "SELECT user_id AS userId, COUNT(*) AS requests, MAX(created_at) AS lastSeen",
            "FROM gen_task WHERE user_id IN",
            "<foreach item='id' collection='userIds' open='(' separator=',' close=')'>",
            "#{id}",
            "</foreach>",
            "GROUP BY user_id",
            "</script>"
    })
    List<UserTaskStat> countByUserIds(@Param("userIds") List<Long> userIds);

    @Select("""
            SELECT COUNT(*) FROM gen_task
            WHERE created_at >= #{start} AND created_at < #{end}
            """)
    long countCreatedBetween(@Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end);

    @Select("""
            SELECT COALESCE(SUM(credit_cost), 0) FROM gen_task
            WHERE created_at >= #{start} AND created_at < #{end}
            """)
    long sumCreditBetween(@Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end);

    @Select("""
            SELECT COUNT(DISTINCT user_id) FROM gen_task
            WHERE created_at >= #{start} AND created_at < #{end}
            """)
    long countDistinctUsersBetween(@Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end);

    @Select("""
            SELECT status AS name, COUNT(*) AS cnt
            FROM gen_task
            WHERE created_at >= #{start} AND created_at < #{end}
            GROUP BY status
            """)
    List<NamedCount> countByStatusBetween(@Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end);

    @Select("""
            SELECT AVG(EXTRACT(EPOCH FROM (finished_at - created_at)))
            FROM gen_task
            WHERE status = 'SUCCEEDED'
              AND finished_at IS NOT NULL
              AND created_at >= #{start} AND created_at < #{end}
            """)
    Double avgDurationSecondsBetween(@Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end);

    @Select("""
            SELECT to_char((created_at AT TIME ZONE 'Asia/Shanghai')::date, 'YYYY-MM-DD') AS name,
                   COUNT(*) AS cnt
            FROM gen_task
            WHERE created_at >= #{start} AND created_at < #{end}
            GROUP BY 1
            ORDER BY 1
            """)
    List<NamedCount> countByDayBetween(@Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end);

    @Select("""
            SELECT EXTRACT(HOUR FROM created_at AT TIME ZONE 'Asia/Shanghai')::int AS hour,
                   COUNT(*) AS cnt
            FROM gen_task
            WHERE created_at >= #{start} AND created_at < #{end}
            GROUP BY 1
            """)
    List<HourCount> countByHourBetween(@Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end);

    @Select("""
            SELECT COALESCE(m.name, '未知模型') AS name, COUNT(*) AS cnt
            FROM gen_task t
            LEFT JOIN ai_model m ON m.id = t.model_id
            WHERE t.created_at >= #{start} AND t.created_at < #{end}
            GROUP BY COALESCE(m.name, '未知模型')
            ORDER BY cnt DESC
            """)
    List<NamedCount> countByModelBetween(@Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end);

    @Select("""
            SELECT COALESCE(params->>'source', '快速创作') AS name, COUNT(*) AS cnt
            FROM gen_task
            WHERE created_at >= #{start} AND created_at < #{end}
            GROUP BY 1
            ORDER BY cnt DESC
            """)
    List<NamedCount> countBySourceBetween(@Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end);
}
