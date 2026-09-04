package cn.aigc.dit.server.service;

import cn.aigc.dit.common.constant.UserStatus;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.common.security.LoginUser;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.server.entity.SysUser;
import cn.aigc.dit.server.mapper.SysUserMapper;
import cn.aigc.dit.server.model.req.LoginReq;
import cn.aigc.dit.server.model.req.RegisterReq;
import cn.aigc.dit.server.model.req.UpdateProfileReq;
import cn.aigc.dit.server.model.resp.TokenResp;
import cn.aigc.dit.server.model.resp.UserProfileResp;
import cn.aigc.dit.server.security.JwtService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AuthService extends ServiceImpl<SysUserMapper, SysUser> {

    private static final long REGISTER_BONUS = 1_000L;

    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public TokenResp login(LoginReq req) {
        String account = req.getAccount().trim();
        SysUser user = findByAccount(account);
        if (user == null || !passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "账号或密码错误");
        }
        if (!UserStatus.ENABLED.equals(user.getStatus())) {
            throw new BizException(ErrorCode.FORBIDDEN, "账号已停用");
        }
        return toToken(user);
    }

    @Transactional
    public TokenResp register(RegisterReq req) {
        String email = req.getEmail().trim().toLowerCase();
        if (count(new LambdaQueryWrapper<SysUser>().eq(SysUser::getEmail, email)) > 0) {
            throw new BizException(ErrorCode.CONFLICT, "邮箱已注册");
        }
        if (count(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, email)) > 0) {
            throw new BizException(ErrorCode.CONFLICT, "账号已存在");
        }
        SysUser user = new SysUser();
        user.setUsername(email);
        user.setEmail(email);
        user.setDisplayName(req.getName().trim());
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        user.setRole(LoginUser.ROLE_CREATOR);
        user.setStatus(UserStatus.ENABLED);
        user.setCreditBalance(REGISTER_BONUS);
        save(user);
        return toToken(user);
    }

    public UserProfileResp currentUser() {
        LoginUser loginUser = UserContext.require();
        SysUser user = getById(loginUser.getUserId());
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户不存在");
        }
        return toProfile(user);
    }

    @Transactional
    public UserProfileResp updateProfile(UpdateProfileReq req) {
        LoginUser loginUser = UserContext.require();
        SysUser user = getById(loginUser.getUserId());
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户不存在");
        }
        if (StringUtils.hasText(req.getName())) {
            user.setDisplayName(req.getName().trim());
        }
        if (StringUtils.hasText(req.getEmail())) {
            String email = req.getEmail().trim().toLowerCase();
            long exists = count(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getEmail, email)
                    .ne(SysUser::getId, user.getId()));
            if (exists > 0) {
                throw new BizException(ErrorCode.CONFLICT, "邮箱已存在");
            }
            user.setEmail(email);
        }
        updateById(user);
        return toProfile(user);
    }

    public SysUser findByAccount(String account) {
        if (!StringUtils.hasText(account)) {
            return null;
        }
        return getOne(new LambdaQueryWrapper<SysUser>()
                .and(w -> w.eq(SysUser::getUsername, account).or().eq(SysUser::getEmail, account))
                .last("LIMIT 1"));
    }

    private TokenResp toToken(SysUser user) {
        LoginUser loginUser = toLoginUser(user);
        TokenResp resp = new TokenResp();
        resp.setToken(jwtService.issue(loginUser));
        resp.setTokenType("Bearer");
        resp.setExpiresIn(jwtService.expireSeconds());
        resp.setUser(toProfile(user));
        return resp;
    }

    public static LoginUser toLoginUser(SysUser user) {
        return new LoginUser(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole());
    }

    public static UserProfileResp toProfile(SysUser user) {
        UserProfileResp resp = new UserProfileResp();
        resp.setId(user.getId());
        resp.setAccount(user.getUsername());
        resp.setName(user.getDisplayName());
        resp.setEmail(user.getEmail());
        resp.setRole(user.getRole());
        resp.setRoleLabel(LoginUser.ROLE_ADMIN.equals(user.getRole()) ? "平台管理员" : "创作者");
        resp.setAdmin(LoginUser.ROLE_ADMIN.equals(user.getRole()));
        resp.setStatus(user.getStatus());
        resp.setCreditBalance(user.getCreditBalance());
        return resp;
    }
}
