package com.lingxi.user.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.lingxi.common.security.AuthProperties;
import com.lingxi.log.application.OperationLogEntry;
import com.lingxi.log.application.OperationLogService;
import com.lingxi.log.domain.OperationResult;
import com.lingxi.user.domain.LingxiUser;
import com.lingxi.user.domain.UserRole;
import com.lingxi.user.domain.repo.UserRepository;

/**
 * 内置管理员预置（M5-5.6）：首次启动幂等创建管理员账号，供运营后台密码登录。
 *
 * <p>约束：①口令来自环境变量（`LINGXI_ADMIN_INIT_PASSWORD`），**未配置则跳过预置并告警**，
 * 绝不使用硬编码口令；②已存在同名账号则跳过（幂等，不覆盖既有口令）；③预置动作本身落操作日志。</p>
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;
    private final OperationLogService operationLogService;

    public AdminBootstrap(UserRepository repository, PasswordEncoder passwordEncoder,
                          AuthProperties properties, OperationLogService operationLogService) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.operationLogService = operationLogService;
    }

    @Override
    public void run(ApplicationArguments args) {
        String tenantId = properties.getDefaultTenant();
        String username = properties.getAdminUsername();
        String initPassword = properties.getAdminInitPassword();
        if (initPassword == null || initPassword.isBlank()) {
            log.warn("未配置内置管理员初始口令（LINGXI_ADMIN_INIT_PASSWORD），跳过管理员预置；"
                    + "如需登录运营后台请先配置该环境变量");
            return;
        }
        if (repository.existsUsername(tenantId, username)) {
            log.info("内置管理员已存在，跳过预置 tenant={} username={}", tenantId, username);
            return;
        }
        LingxiUser admin = new LingxiUser(tenantId, username, passwordEncoder.encode(initPassword),
                properties.getAdminDisplayName(), UserRole.ADMIN);
        repository.save(admin);
        operationLogService.record(OperationLogEntry.of(tenantId, admin.getUserId(), username, UserRole.ADMIN.code(),
                UserAdminService.MODULE_USER, UserAdminService.ACTION_CREATE, "user", admin.getUserId(),
                OperationResult.SUCCESS, "预置内置管理员账号", 0L));
        // 明确告警：默认/初始口令属首次交付凭证，生产环境应尽快更换
        log.warn("已预置内置管理员账号 tenant={} username={}，请登录后立即修改初始口令", tenantId, username);
    }
}
