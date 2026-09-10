package cn.aigc.dit.server.mybatis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@MappedJdbcTypes(JdbcType.OTHER)
public class JsonLongListTypeHandler extends BaseTypeHandler<List<Long>> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<Long>> TYPE = new TypeReference<>() {
    };

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<Long> parameter, JdbcType jdbcType)
            throws SQLException {
        PGobject json = new PGobject();
        json.setType("jsonb");
        try {
            json.setValue(MAPPER.writeValueAsString(parameter == null ? List.of() : parameter));
        } catch (Exception e) {
            throw new SQLException("Error converting Long list to JSONB", e);
        }
        ps.setObject(i, json);
    }

    @Override
    public List<Long> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public List<Long> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public List<Long> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private static List<Long> parse(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<Long> values = MAPPER.readValue(json, TYPE);
            return values == null ? new ArrayList<>() : new ArrayList<>(values);
        } catch (Exception e) {
            throw new SQLException("Error converting JSONB to Long list", e);
        }
    }
}
