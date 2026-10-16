package com.lingxi.log.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lingxi.common.tenant.IsolationViolation;
import com.lingxi.log.application.OperationLogService;
import com.lingxi.log.application.RecordingLogRepository;
import com.lingxi.log.domain.OperationLog;
import com.lingxi.log.domain.OperationResult;

/**
 * 隔离审计落库单测（M5-5.6）：把 M5-5.5 的越权事实从「仅日志」升级为「日志 + 可检索落库」。
 */
class OperationLogIsolationAuditorTest {

    private RecordingLogRepository repository;
    private OperationLogIsolationAuditor auditor;

    @BeforeEach
    void setUp() {
        repository = new RecordingLogRepository();
        auditor = new OperationLogIsolationAuditor(new OperationLogService(repository));
    }

    @Test
    void violationIsPersistedAsDeniedSecurityLog() {
        auditor.record(new IsolationViolation("tenant-1", "u-1", "agent",
                "session", "s-1", "tenant-2", "take_over", "会话归属其他租户"));

        assertEquals(1, repository.logs().size(), "越权事实应落库可检索");
        OperationLog log = repository.logs().get(0);
        assertEquals(OperationLogIsolationAuditor.MODULE_SECURITY, log.module());
        assertEquals(OperationLogIsolationAuditor.ACTION_ISOLATION_DENIED, log.action());
        assertEquals(OperationResult.DENIED.name(), log.result());
        assertEquals("tenant-1", log.tenantId(), "访问方租户");
        assertEquals("u-1", log.userId(), "访问主体");
        assertEquals("s-1", log.resourceId());
        assertTrue(log.detail().contains("tenant-2"), "说明中需含资源实际归属租户，便于审计定性");
    }

    @Test
    void nullViolationIsIgnored() {
        auditor.record(null);
        assertTrue(repository.logs().isEmpty());
    }
}
