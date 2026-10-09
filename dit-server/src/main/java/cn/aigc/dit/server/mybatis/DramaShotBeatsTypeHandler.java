package cn.aigc.dit.server.mybatis;

import cn.aigc.dit.server.drama.DramaShotBeats;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

/**
 * drama_shot.shot_beats 的 jsonb 映射。
 * 必须标上映射类型：这个包会被 type-handlers-package 扫描，没有注解的处理器会被当成所有参数的默认处理器，
 * 字符串和时间参数就会被写成 jsonb。
 */
@MappedJdbcTypes(JdbcType.OTHER)
@MappedTypes(DramaShotBeats.class)
public class DramaShotBeatsTypeHandler extends JsonbTypeHandler {

    public DramaShotBeatsTypeHandler() {
        super(DramaShotBeats.class);
    }
}
