package cn.aigc.dit.server.service;

import cn.aigc.dit.common.constant.CreditReason;
import cn.aigc.dit.common.constant.UserStatus;
import cn.aigc.dit.common.exception.BizException;
import cn.aigc.dit.common.exception.ErrorCode;
import cn.aigc.dit.common.model.resp.PageQueryResp;
import cn.aigc.dit.common.security.LoginUser;
import cn.aigc.dit.common.security.UserContext;
import cn.aigc.dit.server.entity.Asset;
import cn.aigc.dit.server.entity.Creation;
import cn.aigc.dit.server.entity.CreationSession;
import cn.aigc.dit.server.entity.CreditLedger;
import cn.aigc.dit.server.entity.GenTask;
import cn.aigc.dit.server.entity.SysUser;
import cn.aigc.dit.server.mapper.AssetMapper;
import cn.aigc.dit.server.mapper.CreationMapper;
import cn.aigc.dit.server.mapper.CreationSessionMapper;
import cn.aigc.dit.server.mapper.CreditLedgerMapper;
import cn.aigc.dit.server.mapper.GenTaskMapper;
import cn.aigc.dit.server.mapper.SysUserMapper;
import cn.aigc.dit.server.mapper.stat.UserTaskStat;
import cn.aigc.dit.server.model.req.AdminUserCreateReq;
import cn.aigc.dit.server.model.req.AdminUserUpdateReq;
import cn.aigc.dit.server.model.resp.AdminUserResp;
import cn.aigc.dit.server.model.resp.ResetPasswordResp;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminUserService extends ServiceImpl<SysUserMapper, SysUser> {

    private static final String PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final int TEMP_PASSWORD_LEN = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PasswordEncoder passwordEncoder;
    private final CreditLedgerMapper creditLedgerMapper;
    private final GenTaskMapper genTaskMapper;
    private final CreationMapper creationMapper;
    private final CreationSessionMapper creationSessionMapper;
    private final AssetMapper assetMapper;

    public PageQueryResp<AdminUserResp> pageUsers(int pageNum, int pageSize, String keyword, String role, String status) {
        Page<SysUser> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .and(StringUtils.hasText(keyword), w -> w
                        .like(SysUser::getDisplayName, keyword)
                        .or().like(SysUser::getUsername, keyword)
                        .or().like(SysUser::getEmail, keyword))
                .eq(StringUtils.hasText(role), SysUser::getRole, role)
                .eq(StringUtils.hasText(status), SysUser::getStatus, status)
                .orderByDesc(SysUser::getCreatedAt);
        page(page, wrapper);
        List<SysUser> records = page.getRecords();
        Map<Long, UserTaskStat> stats = loadTaskStats(records.stream().map(SysUser::getId).toList());
        List<AdminUserResp> result = records.stream()
                .map(user -> toResp(user, stats.get(user.getId()), null))
                .toList();
        return PageQueryResp.of(page.getCurrent(), page.getSize(), page.getTotal(), result);
    }

    @Transactional
    public AdminUserResp create(AdminUserCreateReq req) {
        String account = normalizeAccount(req.getAccount());
        String email = normalizeEmail(StringUtils.hasText(req.getEmail()) ? req.getEmail() : account);
        assertAccountUnique(account, null);
        if (email != null) {
            assertEmailUnique(email, null);
        }
        String role = normalizeRole(req.getRole());
        String status = normalizeStatus(req.getStatus());
        boolean generated = !StringUtils.hasText(req.getPassword());
        String rawPassword = generated ? generatePassword() : req.getPassword();
        long credits = req.getCreditBalance() == null ? 0L : Math.max(0L, req.getCreditBalance());

        SysUser user = new SysUser();
        user.setUsername(account);
        user.setEmail(email);
        user.setDisplayName(req.getName().trim());
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRole(role);
        user.setStatus(status);
        user.setCreditBalance(credits);
        save(user);
        if (credits > 0) {
            writeLedger(user.getId(), credits, credits, CreditReason.ADJUST, null, "管理员创建账号");
        }
        return toResp(user, null, generated ? rawPassword : null);
    }

    @Transactional
    public AdminUserResp update(Long id, AdminUserUpdateReq req) {
        SysUser user = requireUser(id);
        String account = StringUtils.hasText(req.getAccount()) ? normalizeAccount(req.getAccount()) : user.getUsername();
        String email = normalizeEmail(req.getEmail() != null ? req.getEmail() : user.getEmail());
        assertAccountUnique(account, id);
        if (email != null) {
            assertEmailUnique(email, id);
        }
        String role = req.getRole() != null ? normalizeRole(req.getRole()) : user.getRole();
        String status = req.getStatus() != null ? normalizeStatus(req.getStatus()) : user.getStatus();
        guardSelfAndLastAdmin(user, role, status);

        long oldCredits = user.getCreditBalance() == null ? 0L : user.getCreditBalance();
        long newCredits = req.getCreditBalance() == null ? oldCredits : Math.max(0L, req.getCreditBalance());

        user.setUsername(account);
        user.setEmail(email);
        user.setDisplayName(req.getName().trim());
        user.setRole(role);
        user.setStatus(status);
        user.setCreditBalance(newCredits);
        updateById(user);
        if (newCredits != oldCredits) {
            writeLedger(user.getId(), newCredits - oldCredits, newCredits, CreditReason.ADJUST,
                    null, "管理员调整积分");
        }
        return toResp(user, loadTaskStats(List.of(id)).get(id), null);
    }

    @Transactional
    public AdminUserResp changeStatus(Long id, String status) {
        SysUser user = requireUser(id);
        String next = normalizeStatus(status);
        guardSelfAndLastAdmin(user, user.getRole(), next);
        user.setStatus(next);
        updateById(user);
        return toResp(user, loadTaskStats(List.of(id)).get(id), null);
    }

    @Transactional
    public ResetPasswordResp resetPassword(Long id) {
        SysUser user = requireUser(id);
        String raw = generatePassword();
        user.setPasswordHash(passwordEncoder.encode(raw));
        updateById(user);
        return new ResetPasswordResp(id, raw);
    }

    @Transactional
    public void delete(Long id) {
        SysUser user = requireUser(id);
        Long selfId = UserContext.requireUserId();
        if (Objects.equals(user.getId(), selfId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不能删除当前登录账号");
        }
        if (LoginUser.ROLE_ADMIN.equals(user.getRole()) && countAdmins() <= 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不能删除最后一位管理员");
        }
        if (hasRelatedData(id)) {
            throw new BizException(ErrorCode.CONFLICT, "该用户存在关联数据，请先停用账号");
        }
        removeById(id);
    }

    private boolean hasRelatedData(Long userId) {
        return genTaskMapper.selectCount(new LambdaQueryWrapper<GenTask>().eq(GenTask::getUserId, userId)) > 0
                || creationMapper.selectCount(new LambdaQueryWrapper<Creation>().eq(Creation::getUserId, userId)) > 0
                || creationSessionMapper.selectCount(new LambdaQueryWrapper<CreationSession>().eq(CreationSession::getUserId, userId)) > 0
                || assetMapper.selectCount(new LambdaQueryWrapper<Asset>().eq(Asset::getUserId, userId)) > 0
                || creditLedgerMapper.selectCount(new LambdaQueryWrapper<CreditLedger>().eq(CreditLedger::getUserId, userId)) > 0;
    }

    private void guardSelfAndLastAdmin(SysUser user, String nextRole, String nextStatus) {
        Long selfId = UserContext.requireUserId();
        if (Objects.equals(user.getId(), selfId) && UserStatus.DISABLED.equals(nextStatus)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不能停用当前登录账号");
        }
        boolean wasAdmin = LoginUser.ROLE_ADMIN.equals(user.getRole());
        boolean stillAdmin = LoginUser.ROLE_ADMIN.equals(nextRole) && UserStatus.ENABLED.equals(nextStatus);
        if (wasAdmin && !stillAdmin && countAdmins() <= 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不能停用或降级最后一位管理员");
        }
    }

    private long countAdmins() {
        return count(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRole, LoginUser.ROLE_ADMIN)
                .eq(SysUser::getStatus, UserStatus.ENABLED));
    }

    private SysUser requireUser(Long id) {
        SysUser user = getById(id);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return user;
    }

    private void assertAccountUnique(String account, Long excludeId) {
        long exists = count(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, account)
                .ne(excludeId != null, SysUser::getId, excludeId));
        if (exists > 0) {
            throw new BizException(ErrorCode.CONFLICT, "登录账号已存在");
        }
    }

    private void assertEmailUnique(String email, Long excludeId) {
        long exists = count(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getEmail, email)
                .ne(excludeId != null, SysUser::getId, excludeId));
        if (exists > 0) {
            throw new BizException(ErrorCode.CONFLICT, "邮箱已存在");
        }
    }

    private void writeLedger(Long userId, long changeAmt, long balanceAfter, String reason, String refId, String remark) {
        CreditLedger ledger = new CreditLedger();
        ledger.setUserId(userId);
        ledger.setChangeAmt(changeAmt);
        ledger.setBalanceAfter(balanceAfter);
        ledger.setReason(reason);
        ledger.setRefId(refId);
        ledger.setRemark(remark);
        creditLedgerMapper.insert(ledger);
    }

    private Map<Long, UserTaskStat> loadTaskStats(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return genTaskMapper.countByUserIds(userIds).stream()
                .collect(Collectors.toMap(UserTaskStat::getUserId, s -> s, (a, b) -> a));
    }

    static AdminUserResp toResp(SysUser user, UserTaskStat stat, String temporaryPassword) {
        AdminUserResp resp = new AdminUserResp();
        resp.setId(user.getId());
        resp.setName(user.getDisplayName());
        resp.setAccount(user.getUsername());
        resp.setEmail(user.getEmail());
        resp.setRole(user.getRole());
        resp.setRoleLabel(LoginUser.ROLE_ADMIN.equals(user.getRole()) ? "平台管理员" : "创作者");
        resp.setStatus(user.getStatus());
        resp.setStatusLabel(UserStatus.ENABLED.equals(user.getStatus()) ? "正常" : "已禁用");
        resp.setCredits(user.getCreditBalance() == null ? 0L : user.getCreditBalance());
        resp.setRequests(stat == null || stat.getRequests() == null ? 0L : stat.getRequests());
        resp.setLastSeen(stat == null ? null : stat.getLastSeen());
        resp.setCreatedAt(user.getCreatedAt());
        resp.setTemporaryPassword(temporaryPassword);
        return resp;
    }

    static String normalizeAccount(String account) {
        if (!StringUtils.hasText(account)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "登录账号不能为空");
        }
        return account.trim().toLowerCase();
    }

    static String normalizeEmail(String email) {
        if (!StringUtils.hasText(email)) {
            return null;
        }
        String value = email.trim().toLowerCase();
        return value.contains("@") ? value : null;
    }

    static String normalizeRole(String role) {
        if (!StringUtils.hasText(role) || LoginUser.ROLE_CREATOR.equalsIgnoreCase(role)) {
            return LoginUser.ROLE_CREATOR;
        }
        if (LoginUser.ROLE_ADMIN.equalsIgnoreCase(role)) {
            return LoginUser.ROLE_ADMIN;
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "角色仅支持 ADMIN 或 CREATOR");
    }

    static String normalizeStatus(String status) {
        if (!StringUtils.hasText(status) || UserStatus.ENABLED.equalsIgnoreCase(status)) {
            return UserStatus.ENABLED;
        }
        if (UserStatus.DISABLED.equalsIgnoreCase(status)) {
            return UserStatus.DISABLED;
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "状态仅支持 ENABLED 或 DISABLED");
    }

    static String generatePassword() {
        StringBuilder sb = new StringBuilder(TEMP_PASSWORD_LEN);
        for (int i = 0; i < TEMP_PASSWORD_LEN; i++) {
            sb.append(PASSWORD_CHARS.charAt(RANDOM.nextInt(PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }
}
