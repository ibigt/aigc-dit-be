package cn.aigc.dit.server.security;

import cn.aigc.dit.common.constant.UserStatus;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.server.mapper.SysUserMapper;
import org.springframework.stereotype.Component;

/**
 * 鉴权后校验账号是否仍可用：禁用或删除的用户即使 JWT 未过期也立即失效。
 */
@Component
public class ActiveUserGuard {

    private final SysUserMapper sysUserMapper;

    public ActiveUserGuard(SysUserMapper sysUserMapper) {
        this.sysUserMapper = sysUserMapper;
    }

    public void requireEnabled(Long userId) {
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "登录已过期或令牌无效");
        }
        String status = sysUserMapper.selectStatusById(userId);
        if (status == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "登录已过期或令牌无效");
        }
        if (!UserStatus.ENABLED.equals(status)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "账号已停用");
        }
    }
}
