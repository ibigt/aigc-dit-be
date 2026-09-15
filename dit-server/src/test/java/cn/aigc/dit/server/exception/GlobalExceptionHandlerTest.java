package cn.aigc.dit.server.exception;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    @Test
    void distinguishesForeignKeyAndCheckConstraintViolations() {
        DataIntegrityViolationException foreignKey = violation("23503");
        DataIntegrityViolationException check = violation("23514");

        assertEquals("仍有数据引用该记录，无法删除",
                GlobalExceptionHandler.integrityMessage(foreignKey));
        assertEquals("提交的数据不符合约束要求",
                GlobalExceptionHandler.integrityMessage(check));
    }

    @Test
    void usesGenericMessageWhenSqlStateIsUnavailable() {
        assertEquals("数据操作违反完整性约束",
                GlobalExceptionHandler.integrityMessage(
                        new DataIntegrityViolationException("constraint violation")));
    }

    private static DataIntegrityViolationException violation(String sqlState) {
        return new DataIntegrityViolationException("constraint violation",
                new SQLException("database constraint", sqlState));
    }
}
