package com.lingxi.user.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.lingxi.common.exception.UnauthorizedException;
import com.lingxi.common.security.AuthProperties;
import com.lingxi.common.security.JwtClaims;
import com.lingxi.log.domain.OperationLog;
import com.lingxi.user.domain.LingxiUser;
import com.lingxi.user.domain.UserRole;
import com.lingxi.user.domain.UserStatus;

/**
 * 认证服务单测（M5-5.6）：口令登录、令牌签发、防用户枚举、登录事件审计。
 */
class AuthServiceTest {

    private static final String TENANT = "tenant-1";
    private static final String RAW_PASSWORD = "Admin@12345";

    private PasswordEncoder encoder;
    private InMemoryUserRepository repository;
    private RecordingOperationLogRepository logRepository;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        encoder = new BCryptPasswordEncoder();
        repository = new InMemoryUserRepository();
        logRepository = new RecordingOperationLogRepository();
        AuthProperties properties = new AuthProperties();
        properties.setDefaultTenant(TENANT);
        properties.setAdminUsername("admin");
        authService = new AuthService(repository, encoder, RecordingOperationLogRepository.TestTokens.issuer(),
                RecordingOperationLogRepository.service(logRepository), properties);
        repository.save(new LingxiUser(TENANT, "admin", encoder.encode(RAW_PASSWORD), "平台管理员", UserRole.ADMIN));
    }

    @Test
    void loginIssuesVerifiableTokenAndAudits() {
        AuthService.LoginResult result = authService.login(TENANT, "admin", RAW_PASSWORD);

        assertNotEquals("", result.token(), "应签发令牌");
        JwtClaims claims = RecordingOperationLogRepository.TestTokens.verify(result.token());
        assertEquals(TENANT, claims.tenantId(), "令牌必须携带租户声明（网关据此注入上下文）");
        assertEquals("admin", claims.username());
        assertEquals(UserRole.ADMIN.code(), claims.role());
        assertTrue(claims.hasIdentity(), "令牌身份应完整");
        assertEquals(3600, result.expiresInSeconds());

        assertEquals(1, logRepository.logsOf(AuthService.MODULE_AUTH, AuthService.ACTION_LOGIN).size(),
                "登录成功应落一条审计");
        OperationLog log = logRepository.logsOf(AuthService.MODULE_AUTH, AuthService.ACTION_LOGIN).get(0);
        assertEquals("SUCCESS", log.result());
        assertTrue(log.detail().isBlank(), "成功路径说明应为空");
    }

    @Test
    void loginFailureIsUniformAndAudited() {
        // 口令错误
        UnauthorizedException wrongPassword = assertThrows(UnauthorizedException.class,
                () -> authService.login(TENANT, "admin", "wrong-password"));
        assertEquals("账号或密码错误", wrongPassword.getMessage(), "不得泄露「口令错误」这一具体原因");

        // 用户不存在：提示必须与口令错误一致（防用户枚举）
        UnauthorizedException unknownUser = assertThrows(UnauthorizedException.class,
                () -> authService.login(TENANT, "no-such-user", RAW_PASSWORD));
        assertEquals(wrongPassword.getMessage(), unknownUser.getMessage());

        assertEquals(2, logRepository.logsOf(AuthService.MODULE_AUTH, AuthService.ACTION_LOGIN).size(),
                "失败同样要留审计（含登录名与来源）");
        assertTrue(logRepository.logsOf(AuthService.MODULE_AUTH, AuthService.ACTION_LOGIN).stream()
                        .allMatch(log -> "FAILED".equals(log.result())),
                "失败审计结果应为 FAILED");
    }

    @Test
    void disabledAccountCannotLogin() {
        LingxiUser admin = repository.findByUsername(TENANT, "admin").orElseThrow();
        repository.updateStatus(TENANT, admin.getUserId(), UserStatus.DISABLED, System.currentTimeMillis());

        assertThrows(UnauthorizedException.class, () -> authService.login(TENANT, "admin", RAW_PASSWORD),
                "禁用账号必须拒绝登录");
    }

    @Test
    void loginWithoutTenantFallsBackToDefaultTenant() {
        AuthService.LoginResult result = authService.login("", "admin", RAW_PASSWORD);
        assertEquals(TENANT, RecordingOperationLogRepository.TestTokens.verify(result.token()).tenantId(),
                "未指定租户时归属默认租户");
    }

    @Test
    void auditFailureDoesNotBreakLogin() {
        logRepository.failOnAppend = true;

        // 审计是旁路：落库失败不得影响登录主链路
        AuthService.LoginResult result = authService.login(TENANT, "admin", RAW_PASSWORD);
        assertNotEquals("", result.token(), "审计失败时仍应正常签发令牌");
    }

    @Test
    void currentUserReadsFromTrustedContext() {
        LingxiUser admin = repository.findByUsername(TENANT, "admin").orElseThrow();
        com.lingxi.common.tenant.TenantContext.set(
                new com.lingxi.common.tenant.TenantIdentity(TENANT, admin.getUserId(), UserRole.ADMIN.code()));
        try {
            assertEquals("admin", authService.currentUser().getUsername());
        } finally {
            com.lingxi.common.tenant.TenantContext.clear();
        }
    }

    @Test
    void currentUserRejectsMissingContext() {
        com.lingxi.common.tenant.TenantContext.clear();
        assertThrows(RuntimeException.class, authService::currentUser, "缺少上下文应拒绝");
    }
}
