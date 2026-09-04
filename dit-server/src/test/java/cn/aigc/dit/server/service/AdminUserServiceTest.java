package cn.aigc.dit.server.service;

import cn.aigc.dit.common.constant.UserStatus;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.security.LoginUser;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.server.entity.SysUser;
import cn.aigc.dit.server.mapper.AssetMapper;
import cn.aigc.dit.server.mapper.CreationMapper;
import cn.aigc.dit.server.mapper.CreationSessionMapper;
import cn.aigc.dit.server.mapper.CreditLedgerMapper;
import cn.aigc.dit.server.mapper.GenTaskMapper;
import cn.aigc.dit.server.mapper.SysUserMapper;
import cn.aigc.dit.server.model.req.AdminUserCreateReq;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock
    private SysUserMapper userMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private CreditLedgerMapper creditLedgerMapper;
    @Mock
    private GenTaskMapper genTaskMapper;
    @Mock
    private CreationMapper creationMapper;
    @Mock
    private CreationSessionMapper creationSessionMapper;
    @Mock
    private AssetMapper assetMapper;

    private AdminUserService service;

    @BeforeEach
    void setUp() {
        service = new AdminUserService(
                passwordEncoder, creditLedgerMapper, genTaskMapper,
                creationMapper, creationSessionMapper, assetMapper);
        ReflectionTestUtils.setField(service, "baseMapper", userMapper);
        UserContext.set(new LoginUser(1L, "admin", "admin@localhost", "管理员", LoginUser.ROLE_ADMIN));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void createGeneratesPasswordAndNormalizesAccount() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        when(userMapper.insert(any(SysUser.class))).thenAnswer(invocation -> {
            SysUser user = invocation.getArgument(0);
            user.setId(9L);
            assertEquals("hashed", user.getPasswordHash());
            return 1;
        });

        AdminUserCreateReq req = new AdminUserCreateReq();
        req.setName("林清");
        req.setAccount("Lin@Example.com");
        req.setCreditBalance(200L);

        var resp = service.create(req);
        assertEquals("lin@example.com", resp.getAccount());
        assertEquals("lin@example.com", resp.getEmail());
        assertEquals(LoginUser.ROLE_CREATOR, resp.getRole());
        assertNotNull(resp.getTemporaryPassword());
        assertEquals(10, resp.getTemporaryPassword().length());

        verify(creditLedgerMapper).insert(any(cn.aigc.dit.server.entity.CreditLedger.class));
    }

    @Test
    void rejectInvalidRole() {
        assertThrows(BizException.class, () -> AdminUserService.normalizeRole("访客"));
    }

    @Test
    void cannotDisableSelf() {
        SysUser self = new SysUser();
        self.setId(1L);
        self.setRole(LoginUser.ROLE_ADMIN);
        self.setStatus(UserStatus.ENABLED);
        when(userMapper.selectById(1L)).thenReturn(self);

        assertThrows(BizException.class, () -> service.changeStatus(1L, UserStatus.DISABLED));
    }
}
