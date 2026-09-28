package cn.aigc.dit.server.security;

import cn.aigc.dit.common.constant.UserStatus;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.security.LoginUser;
import cn.aigc.dit.server.entity.SysUser;
import cn.aigc.dit.server.mapper.SysUserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    @Test
    void acceptsQueryTokenOnlyForBrowserLoadedMediaAndCaptionResources() {
        assertTrue(accepts("/api/media"));
        assertTrue(accepts("/api/drama/shots/12/captions.vtt"));
        assertTrue(accepts("/api/drama/episodes/34/film/captions.vtt"));

        assertFalse(accepts("/api/drama/shots/12"));
        assertFalse(accepts("/api/drama/episodes/34/film"));
        assertFalse(accepts("/api/drama/shots/not-a-number/captions.vtt"));
    }

    @Test
    void refreshesRoleFromCurrentDatabaseRecord() {
        JwtService jwtService = mock(JwtService.class);
        SysUserMapper mapper = mock(SysUserMapper.class);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService, mapper);
        LoginUser tokenUser = new LoginUser(7L, "old", "old@example.com", "Old", "ADMIN");
        SysUser stored = new SysUser();
        stored.setId(7L);
        stored.setUsername("current");
        stored.setEmail("current@example.com");
        stored.setDisplayName("Current");
        stored.setRole("CREATOR");
        stored.setStatus(UserStatus.ENABLED);
        when(mapper.selectById(7L)).thenReturn(stored);

        LoginUser current = filter.requireCurrent(tokenUser);

        assertEquals("CREATOR", current.getRole());
        assertEquals("current@example.com", current.getEmail());
    }

    @Test
    void rejectsDisabledOrDeletedAccountsImmediately() {
        JwtService jwtService = mock(JwtService.class);
        SysUserMapper mapper = mock(SysUserMapper.class);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService, mapper);
        LoginUser tokenUser = new LoginUser(8L, "user", "u@example.com", "User", "CREATOR");
        SysUser disabled = new SysUser();
        disabled.setId(8L);
        disabled.setStatus(UserStatus.DISABLED);
        when(mapper.selectById(8L)).thenReturn(disabled);
        assertThrows(BizException.class, () -> filter.requireCurrent(tokenUser));

        when(mapper.selectById(8L)).thenReturn(null);
        assertThrows(BizException.class, () -> filter.requireCurrent(tokenUser));
    }

    private static boolean accepts(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        return JwtAuthenticationFilter.acceptsQueryToken(request);
    }
}
