package cn.aigc.dit.server.security;

import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.security.LoginUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("0123456789abcdef0123456789abcdef");
        properties.setExpireSeconds(3600);
        jwtService = new JwtService(properties);
    }

    @Test
    void issueAndParseRoundTrip() {
        LoginUser user = new LoginUser(1L, "admin", "admin@localhost", "系统管理员", LoginUser.ROLE_ADMIN);
        String token = jwtService.issue(user);
        LoginUser parsed = jwtService.parse(token);
        assertEquals(1L, parsed.getUserId());
        assertEquals("admin", parsed.getUsername());
        assertEquals("ADMIN", parsed.getRole());
        assertTrue(parsed.isAdmin());
    }

    @Test
    void rejectTamperedToken() {
        LoginUser user = new LoginUser(1L, "admin", "admin@localhost", "系统管理员", LoginUser.ROLE_ADMIN);
        String token = jwtService.issue(user) + "x";
        assertThrows(BizException.class, () -> jwtService.parse(token));
    }
}
