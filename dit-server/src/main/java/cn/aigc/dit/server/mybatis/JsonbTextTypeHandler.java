package cn.aigc.dit.server.mybatis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * jsonb 列 ↔ 「一整段 JSON 文本」的映射。
 *
 * <p>和 {@link JsonbTypeHandler} 的分工：那个把列当 JSON 反序列化成对象/Map/List，
 * 只能配非 String 字段；这个保持文本形态，String 字段（{@code agent_run.supervisor_issues}）
 * 该用它。给 String 字段误用 {@code JsonbTypeHandler} 的后果是：列里存的是 JSON 对象时，
 * 读出来是个 Map，MyBatis 塞不进 String 属性，直接抛
 * {@code argument type mismatch}，后台接口整页 500。
 *
 * <p>读的时候要兼容两种历史写法（同一列里两种都存在过）：
 * <ul>
 *   <li>整段 JSON（数组或对象）—— 原样当文本返回；</li>
 *   <li>JSON 字符串套娃（{@code "[{\"field\":\"...}]"}）—— 老的 {@code JsonbTypeHandler}
 *       写进去的，把里面的 JSON 文本拆出来。</li>
 * </ul>
 * 两种写法读出来的都是同一段 JSON 文本，调用方无感。
 */
@MappedJdbcTypes(JdbcType.OTHER)
@MappedTypes(String.class)
public class JsonbTextTypeHandler extends BaseTypeHandler<String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        PGobject json = new PGobject();
        json.setType("jsonb");
        json.setValue(toJsonbValue(parameter));
        ps.setObject(i, json);
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return unwrap(rs.getString(columnName));
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return unwrap(rs.getString(columnIndex));
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return unwrap(cs.getString(columnIndex));
    }

    /** 合法 JSON 就按 JSON 存；不是合法 JSON 就当字符串存（jsonb 只吃合法 JSON 文本）。 */
    private String toJsonbValue(String text) {
        if (text == null || text.isBlank()) {
            return "{}";
        }
        try {
            MAPPER.readTree(text);
            return text;
        } catch (Exception e) {
            try {
                return MAPPER.writeValueAsString(text);
            } catch (Exception ignored) {
                return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            }
        }
    }

    /** 列里存的是 JSON 字符串套娃时，把里面的 JSON 文本拆出来。 */
    private String unwrap(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.strip();
        if (!text.startsWith("\"")) {
            return raw;
        }
        try {
            return MAPPER.readValue(text, String.class);
        } catch (Exception e) {
            return raw;
        }
    }
}
