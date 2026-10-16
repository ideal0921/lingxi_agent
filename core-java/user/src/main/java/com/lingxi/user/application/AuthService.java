package com.lingxi.user.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.lingxi.common.exception.UnauthorizedException;
import com.lingxi.common.security.AuthProperties;
import com.lingxi.common.security.JwtIssuer;
import com.lingxi.common.tenant.TenantContext;
import com.lingxi.log.application.OperationLogEntry;
import com.lingxi.log.application.OperationLogService;
import com.lingxi.log.domain.OperationResult;
import com.lingxi.user.domain.LingxiUser;
import com.lingxi.user.domain.repo.UserRepository;

/**
 * 认证应用服务（M5-5.6）：口令登录、令牌签发与当前身份查询，全部登录事件落操作日志。
 *
 * <p>安全约束：①登录失败统一抛 {@link UnauthorizedException}（不区分「用户不存在 / 口令错误 /
 * 账号禁用」，防用户枚举与撞库探测）；②明文口令只用于比对，绝不落库、绝不入日志。</p>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 审计模块与动作常量（与日志页筛选项对应）。 */
    public static final String MODULE_AUTH = "auth";
    public static final String ACTION_LOGIN = "login";
    public static final String ACTION_LOGOUT = "logout";

    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final JwtIssuer jwtIssuer;
    private final OperationLogService operationLogService;
    private final AuthProperties properties;

    public AuthService(UserRepository repository, PasswordEncoder passwordEncoder, JwtIssuer jwtIssuer,
                       OperationLogService operationLogService, AuthProperties properties) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.jwtIssuer = jwtIssuer;
        this.operationLogService = operationLogService;
        this.properties = properties;
    }

    /**
     * 口令登录。
     *
     * @param tenantId    租户（可空，空则回落默认租户）
     * @param username    登录名
     * @param rawPassword 明文口令（仅用于比对）
     * @return 令牌与账号信息
     * @throws UnauthorizedException 凭证不正确或账号不可用（统一提示）
     */
    public LoginResult login(String tenantId, String username, String rawPassword) {
        long start = System.currentTimeMillis();
        String tenant = tenantId == null || tenantId.isBlank() ? properties.getDefaultTenant() : tenantId.trim();
        String name = username == null ? "" : username.trim();

        LingxiUser user = repository.findByUsername(tenant, name).orElse(null);
        if (user == null || !user.canLogin() || rawPassword == null || rawPassword.isBlank()
                || !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            auditLogin(tenant, "", name, "", OperationResult.FAILED,
                    "账号或密码错误（或账号已禁用）", start);
            log.warn("[auth] 登录失败 tenant={} username={}", tenant, name);
            throw new UnauthorizedException("账号或密码错误");
        }

        long now = System.currentTimeMillis();
        repository.updateLastLogin(tenant, user.getUserId(), now);
        String token = jwtIssuer.issue(tenant, user.getUserId(), user.getUsername(), user.getRole().code());
        auditLogin(tenant, user.getUserId(), user.getUsername(), user.getRole().code(),
                OperationResult.SUCCESS, "", start);
        log.info("[auth] 登录成功 tenant={} username={} role={}", tenant, user.getUsername(), user.getRole().code());
        return new LoginResult(token, jwtIssuer.tokenTtlSeconds(), user);
    }

    /**
     * 取当前登录账号（依据可信租户上下文中的用户 ID 回查）。
     *
     * @throws UnauthorizedException 上下文缺失或账号已被删除/禁用
     */
    public LingxiUser currentUser() {
        String tenantId = TenantContext.requireTenantId();
        String userId = TenantContext.current().userId();
        if (userId.isBlank()) {
            throw new UnauthorizedException("当前身份缺少用户标识");
        }
        return repository.findById(userId, tenantId)
                .filter(LingxiUser::canLogin)
                .orElseThrow(() -> new UnauthorizedException("账号不存在或已被禁用"));
    }

    /** 登出：无状态令牌无需服务端失效，仅记录审计（前端负责清除本地令牌）。 */
    public void logout() {
        String tenantId = TenantContext.currentTenantId();
        String userId = TenantContext.current().userId();
        operationLogService.record(OperationLogEntry.ofCurrent(MODULE_AUTH, ACTION_LOGOUT, "", "",
                OperationResult.SUCCESS, "", 0L));
        log.info("[auth] 登出 tenant={} userId={}", tenantId, userId);
    }

    /** 登录审计：成败均记录（失败时用户 ID 为空，至少留下登录名与来源 IP）。 */
    private void auditLogin(String tenantId, String userId, String username, String role,
                            OperationResult result, String detail, long startMs) {
        operationLogService.record(OperationLogEntry.of(tenantId, userId, username, role,
                MODULE_AUTH, ACTION_LOGIN, "", "", result, detail, System.currentTimeMillis() - startMs));
    }

    /**
     * 登录结果。
     *
     * @param token            访问令牌（HS256）
     * @param expiresInSeconds 有效期（秒）
     * @param user             账号实体（含角色与租户）
     */
    public record LoginResult(String token, long expiresInSeconds, LingxiUser user) {
    }
}
