package cn.aigc.dit.server.security;

import cn.aigc.dit.common.constant.UserStatus;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.server.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActiveUserGuardTest {

    private SysUserMapper sysUserMapper;
    private ActiveUserGuard guard;

    @BeforeEach
    void setUp() {
        sysUserMapper = mock(SysUserMapper.class);
        guard = new ActiveUserGuard(sysUserMapper);
    }

    @Test
    void allowsEnabledUser() {
        when(sysUserMapper.selectStatusById(7L)).thenReturn(UserStatus.ENABLED);
        assertDoesNotThrow(() -> guard.requireEnabled(7L));
    }

    @Test
    void rejectsDisabledUser() {
        when(sysUserMapper.selectStatusById(7L)).thenReturn(UserStatus.DISABLED);
        BizException error = assertThrows(BizException.class, () -> guard.requireEnabled(7L));
        assertEquals(ErrorCode.UNAUTHORIZED, error.getCode());
        assertEquals("账号已停用", error.getMessage());
    }

    @Test
    void rejectsMissingUser() {
        when(sysUserMapper.selectStatusById(7L)).thenReturn(null);
        BizException error = assertThrows(BizException.class, () -> guard.requireEnabled(7L));
        assertEquals(ErrorCode.UNAUTHORIZED, error.getCode());
    }

    @Test
    void rejectsNullUserId() {
        BizException error = assertThrows(BizException.class, () -> guard.requireEnabled(null));
        assertEquals(ErrorCode.UNAUTHORIZED, error.getCode());
    }
}
