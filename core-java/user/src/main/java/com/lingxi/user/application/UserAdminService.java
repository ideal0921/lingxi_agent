package com.lingxi.user.application;

import java.util.List;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.lingxi.common.exception.BizException;
import com.lingxi.common.result.PageResult;
import com.lingxi.common.security.AuthProperties;
import com.lingxi.common.tenant.TenantContext;
import com.lingxi.log.application.OperationLogEntry;
import com.lingxi.log.application.OperationLogService;
import com.lingxi.log.domain.OperationResult;
import com.lingxi.user.domain.LingxiUser;
import com.lingxi.user.domain.UserRole;
import com.lingxi.user.domain.UserStatus;
import com.lingxi.user.domain.repo.UserRepository;

/**
 * 用户管理应用服务（M5-5.6，管理后台）：创建账号、重置口令、启用/禁用与分页查询。
 *
 * <p>约束：①一律在**可信租户上下文**内操作（不接受前端指定其他租户）；
 * ②口令以 BCrypt 哈希存储，明文仅存在于请求生命周期内；③内置管理员账号不可被禁用（保护兜底入口）；
 * ④所有管理动作落操作日志。</p>
 */
@Service
public class UserAdminService {

    /** 审计模块与动作常量（与日志页筛选项对应）。 */
    public static final String MODULE_USER = "user";
    public static final String ACTION_CREATE = "create";
    public static final String ACTION_RESET_PASSWORD = "reset_password";
    public static final String ACTION_CHANGE_STATUS = "change_status";

    /** 单页上限（防管理后台一次拉取过大）。 */
    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** 口令长度约束：下限 8（抵御弱口令），上限 64（BCrypt 输入上限内）。 */
    private static final int MIN_PASSWORD_LEN = 8;
    private static final int MAX_PASSWORD_LEN = 64;

    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final OperationLogService operationLogService;
    private final AuthProperties properties;

    public UserAdminService(UserRepository repository, PasswordEncoder passwordEncoder,
                            OperationLogService operationLogService, AuthProperties properties) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.operationLogService = operationLogService;
        this.properties = properties;
    }

    /**
     * 创建账号。
     *
     * @throws BizException 参数非法或用户名已存在（409）
     */
    public LingxiUser create(String username, String rawPassword, String displayName, String roleCode) {
        long start = System.currentTimeMillis();
        String tenantId = TenantContext.requireTenantId();
        validatePassword(rawPassword);
        LingxiUser.validateUsername(username);
        UserRole role = UserRole.fromCode(roleCode);
        if (role == null) {
            throw new BizException(400, "非法角色（仅支持 admin/agent/user）");
        }
        if (repository.existsUsername(tenantId, username.trim())) {
            audit(ACTION_CREATE, OperationResult.FAILED, "用户名已存在: " + username, start);
            throw new BizException(409, "用户名已存在");
        }
        LingxiUser user;
        try {
            user = new LingxiUser(tenantId, username, passwordEncoder.encode(rawPassword), displayName, role);
        } catch (IllegalArgumentException e) {
            audit(ACTION_CREATE, OperationResult.FAILED, e.getMessage(), start);
            throw new BizException(400, e.getMessage());
        }
        repository.save(user);
        audit(ACTION_CREATE, OperationResult.SUCCESS, "创建账号 " + user.getUsername() + "（角色 " + role.code() + "）", start);
        return user;
    }

    /** 分页查询本租户账号（keyword 匹配登录名或展示名）。 */
    public PageResult<LingxiUser> list(String keyword, int page, int size) {
        String tenantId = TenantContext.requireTenantId();
        int normalizedPage = Math.max(page, 1);
        int normalizedSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        long total = repository.count(tenantId, keyword);
        List<LingxiUser> items = total == 0 ? List.of()
                : repository.list(tenantId, keyword, (normalizedPage - 1) * normalizedSize, normalizedSize);
        return PageResult.of(total, normalizedPage, normalizedSize, items);
    }

    /** 重置账号口令（管理员操作，需重新登录生效）。 */
    public void resetPassword(String userId, String rawPassword) {
        long start = System.currentTimeMillis();
        validatePassword(rawPassword);
        LingxiUser user = requireUser(userId);
        repository.updatePasswordHash(user.getTenantId(), user.getUserId(),
                passwordEncoder.encode(rawPassword), System.currentTimeMillis());
        audit(ACTION_RESET_PASSWORD, OperationResult.SUCCESS, "重置口令 " + user.getUsername(), start);
    }

    /**
     * 启用/禁用账号。
     *
     * @throws BizException 内置管理员不可禁用；账号不存在
     */
    public LingxiUser changeStatus(String userId, String statusName) {
        long start = System.currentTimeMillis();
        UserStatus target = UserStatus.fromName(statusName);
        if (target == null) {
            throw new BizException(400, "非法状态（仅支持 ENABLED/DISABLED）");
        }
        LingxiUser user = requireUser(userId);
        if (target == UserStatus.DISABLED && properties.getAdminUsername().equals(user.getUsername())) {
            audit(ACTION_CHANGE_STATUS, OperationResult.DENIED, "拒绝禁用内置管理员账号", start);
            throw new BizException(403, "内置管理员账号不可禁用");
        }
        repository.updateStatus(user.getTenantId(), user.getUserId(), target, System.currentTimeMillis());
        audit(ACTION_CHANGE_STATUS, OperationResult.SUCCESS,
                "账号 " + user.getUsername() + " → " + target.name(), start);
        return new LingxiUser(user.getUserId(), user.getTenantId(), user.getUsername(), user.getPasswordHash(),
                user.getDisplayName(), user.getRole(), target,
                user.getCreatedAtMs(), System.currentTimeMillis(), user.getLastLoginAtMs());
    }

    /** 租户内按 ID 取账号，不存在时报错（不泄露其他租户账号信息）。 */
    private LingxiUser requireUser(String userId) {
        String tenantId = TenantContext.requireTenantId();
        if (userId == null || userId.isBlank()) {
            throw new BizException(400, "userId 不能为空");
        }
        return repository.findById(userId, tenantId)
                .orElseThrow(() -> new BizException(404, "账号不存在"));
    }

    /** 口令强度校验：长度区间约束（更复杂的组合策略由业务策略后续扩展）。 */
    private void validatePassword(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new BizException(400, "口令不能为空");
        }
        if (rawPassword.length() < MIN_PASSWORD_LEN || rawPassword.length() > MAX_PASSWORD_LEN) {
            throw new BizException(400, "口令长度必须在 " + MIN_PASSWORD_LEN + "~" + MAX_PASSWORD_LEN + " 之间");
        }
    }

    /** 管理动作审计（detail 不含口令）。 */
    private void audit(String action, OperationResult result, String detail, long startMs) {
        operationLogService.record(OperationLogEntry.ofCurrent(MODULE_USER, action, "user", "",
                result, detail, System.currentTimeMillis() - startMs));
    }
}
