package cn.aigc.dit.server.bootstrap;

import cn.aigc.dit.common.constant.UserStatus;
import cn.aigc.dit.common.security.LoginUser;
import cn.aigc.dit.server.config.AdminProperties;
import cn.aigc.dit.server.entity.SysUser;
import cn.aigc.dit.server.service.AuthService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrap implements ApplicationRunner {

    private static final long ADMIN_CREDITS = 100_000L;

    private final AuthService authService;
    private final AdminProperties adminProperties;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        String username = adminProperties.getUsername();
        long exists = authService.count(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (exists > 0) {
            log.info("Admin account already exists: {}", username);
            return;
        }
        SysUser admin = new SysUser();
        admin.setUsername(username);
        admin.setEmail(adminProperties.getEmail());
        admin.setDisplayName(adminProperties.getDisplayName());
        admin.setPasswordHash(passwordEncoder.encode(adminProperties.getPassword()));
        admin.setRole(LoginUser.ROLE_ADMIN);
        admin.setStatus(UserStatus.ENABLED);
        admin.setCreditBalance(ADMIN_CREDITS);
        authService.save(admin);
        log.info("Bootstrapped admin account: {}", username);
    }
}
