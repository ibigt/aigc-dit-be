package cn.aigc.dit.server.mybatis;

import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class JsonbTypeHandlerTest {

    @Test
    void writesListAsJsonb() throws Exception {
        JsonbTypeHandler handler = new JsonbTypeHandler(List.class);
        PreparedStatement ps = mock(PreparedStatement.class);

        handler.setNonNullParameter(ps, 1, List.of("image", "video"), null);

        var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(ps).setObject(eq(1), captor.capture());
        PGobject pg = assertInstanceOf(PGobject.class, captor.getValue());
        assertEquals("jsonb", pg.getType());
        assertEquals("[\"image\",\"video\"]", pg.getValue());
    }

    @Test
    void writesMapAsJsonb() throws Exception {
        JsonbTypeHandler handler = new JsonbTypeHandler(Map.class);
        PreparedStatement ps = mock(PreparedStatement.class);

        handler.setNonNullParameter(ps, 1, Map.of("ratio", "16:9"), null);

        var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(ps).setObject(eq(1), captor.capture());
        PGobject pg = assertInstanceOf(PGobject.class, captor.getValue());
        assertEquals("jsonb", pg.getType());
        assertEquals("{\"ratio\":\"16:9\"}", pg.getValue());
    }
}
