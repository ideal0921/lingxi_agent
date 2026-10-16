package com.lingxi.user.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.lingxi.common.exception.BizException;
import com.lingxi.common.result.PageResult;
import com.lingxi.common.security.AuthProperties;
import com.lingxi.common.tenant.TenantContext;
import com.lingxi.common.tenant.TenantIdentity;
import com.lingxi.user.domain.LingxiUser;
import com.lingxi.user.domain.UserRole;
import com.lingxi.user.domain.UserStatus;

/**
 * 用户管理单测（M5-5.6）：创建/分页/重置口令/启停，含口令哈希、租户隔离与内置管理员保护。
 */
class UserAdminServiceTest {

    private static final String TENANT = "tenant-1";
    private static final String OTHER_TENANT = "tenant-2";

    private PasswordEncoder encoder;
    private InMemoryUserRepository repository;
    private RecordingOperationLogRepository logRepository;
    private UserAdminService service;

    @BeforeEach
    void setUp() {
        encoder = new BCryptPasswordEncoder();
        repository = new InMemoryUserRepository();
        logRepository = new RecordingOperationLogRepository();
        AuthProperties properties = new AuthProperties();
        properties.setDefaultTenant(TENANT);
        properties.setAdminUsername("admin");
        service = new UserAdminService(repository, encoder, RecordingOperationLogRepository.service(logRepository), properties);
        repository.save(new LingxiUser(TENANT, "admin", encoder.encode("Admin@12345"), "平台管理员", UserRole.ADMIN));
        TenantContext.set(new TenantIdentity(TENANT, "operator-1", UserRole.ADMIN.code()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void createHashesPasswordAndAudits() {
        LingxiUser created = service.create("agent-01", "Agent@12345", "客服小张", UserRole.AGENT.code());

        assertEquals(TENANT, created.getTenantId(), "租户取可信上下文，前端不可指定");
        assertEquals(UserRole.AGENT, created.getRole());
        assertEquals(UserStatus.ENABLED, created.getStatus());
        assertNotEquals("Agent@12345", created.getPasswordHash(), "口令必须以哈希存储");
        assertTrue(encoder.matches("Agent@12345", created.getPasswordHash()), "哈希需可用原口令校验");
        assertEquals(1, logRepository.logsOf(UserAdminService.MODULE_USER, UserAdminService.ACTION_CREATE).size(),
                "创建动作应落审计");
    }

    @Test
    void createRejectsDuplicateUsername() {
        service.create("agent-01", "Agent@12345", "", UserRole.AGENT.code());

        BizException duplicate = assertThrows(BizException.class,
                () -> service.create("agent-01", "Agent@12345", "", UserRole.AGENT.code()));
        assertEquals(409, duplicate.getCode(), "用户名重复应为 409 冲突");
    }

    @Test
    void createRejectsWeakPasswordAndIllegalRole() {
        assertEquals(400, assertThrows(BizException.class,
                () -> service.create("agent-02", "short", "", UserRole.AGENT.code())).getCode(),
                "弱口令（短于 8 位）应拒绝");
        assertEquals(400, assertThrows(BizException.class,
                () -> service.create("agent-03", "Agent@12345", "", "superman")).getCode(),
                "非法角色应拒绝");
    }

    @Test
    void listIsScopedToCallerTenant() {
        service.create("agent-01", "Agent@12345", "", UserRole.AGENT.code());
        // 另一租户的账号（同一仓储、不同租户）不应出现在列表
        repository.save(new LingxiUser(OTHER_TENANT, "agent-99", encoder.encode("Agent@12345"), "", UserRole.AGENT));

        PageResult<LingxiUser> page = service.list(null, 1, 20);

        assertEquals(2, page.total(), "只应看到本租户 2 个账号（admin + agent-01）");
        assertTrue(page.items().stream().allMatch(user -> TENANT.equals(user.getTenantId())),
                "列表中不得出现其他租户账号");

        PageResult<LingxiUser> filtered = service.list("agent", 1, 20);
        assertEquals(1, filtered.total(), "关键词应支持按登录名过滤");
    }

    @Test
    void resetPasswordReplacesHash() {
        LingxiUser user = service.create("agent-01", "Agent@12345", "", UserRole.AGENT.code());

        service.resetPassword(user.getUserId(), "NewPass@123");

        LingxiUser reloaded = repository.findById(user.getUserId(), TENANT).orElseThrow();
        assertTrue(encoder.matches("NewPass@123", reloaded.getPasswordHash()), "新口令应生效");
        assertEquals(1, logRepository.logsOf(UserAdminService.MODULE_USER,
                UserAdminService.ACTION_RESET_PASSWORD).size(), "重置口令应落审计");
    }

    @Test
    void changeStatusDisablesAccount() {
        LingxiUser user = service.create("agent-01", "Agent@12345", "", UserRole.AGENT.code());

        LingxiUser updated = service.changeStatus(user.getUserId(), "DISABLED");

        assertEquals(UserStatus.DISABLED, updated.getStatus());
        assertEquals(UserStatus.DISABLED, repository.findById(user.getUserId(), TENANT).orElseThrow().getStatus());
    }

    @Test
    void builtinAdminCannotBeDisabled() {
        LingxiUser admin = repository.findByUsername(TENANT, "admin").orElseThrow();

        BizException denied = assertThrows(BizException.class,
                () -> service.changeStatus(admin.getUserId(), "DISABLED"));
        assertEquals(403, denied.getCode(), "内置管理员不可禁用（保护兜底入口）");
        assertEquals(UserStatus.ENABLED, repository.findById(admin.getUserId(), TENANT).orElseThrow().getStatus());
        assertEquals("DENIED", logRepository.logsOf(UserAdminService.MODULE_USER,
                UserAdminService.ACTION_CHANGE_STATUS).get(0).result(), "拒绝动作也应留审计");
    }

    @Test
    void crossTenantUserIsNotVisible() {
        LingxiUser other = repository.save(new LingxiUser(OTHER_TENANT, "agent-99",
                encoder.encode("Agent@12345"), "", UserRole.AGENT));

        BizException notFound = assertThrows(BizException.class,
                () -> service.changeStatus(other.getUserId(), "DISABLED"));
        assertEquals(404, notFound.getCode(), "跨租户账号对外表现为不存在");
    }
}
