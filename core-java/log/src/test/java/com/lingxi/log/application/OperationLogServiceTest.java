package com.lingxi.log.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lingxi.common.result.PageResult;
import com.lingxi.log.domain.OperationLog;
import com.lingxi.log.domain.OperationResult;

/**
 * 操作日志服务单测（M5-5.6）：写入旁路、租户隔离查询与分页参数归一化。
 */
class OperationLogServiceTest {

    private static final String TENANT = "tenant-1";

    private RecordingLogRepository repository;
    private OperationLogService service;

    @BeforeEach
    void setUp() {
        repository = new RecordingLogRepository();
        service = new OperationLogService(repository);
    }

    @Test
    void recordPersistsEntryWithGeneratedIdAndTimestamp() {
        service.record(OperationLogEntry.of(TENANT, "u-1", "admin", "admin", "auth", "login",
                "", "", OperationResult.SUCCESS, "", 12));

        assertEquals(1, repository.logs().size());
        OperationLog log = repository.logs().get(0);
        assertNotNull(log.logId(), "应生成日志 ID");
        assertTrue(log.createdAtMs() > 0, "应记录时间戳");
        assertEquals("tenant-1", log.tenantId());
        assertEquals(12, log.elapsedMs());
    }

    @Test
    void recordFailureIsSwallowed() {
        repository.failOnAppend = true;

        // 审计是旁路：落库异常必须被吞掉，绝不冒泡到业务调用方
        assertDoesNotThrow(() -> service.record(OperationLogEntry.of(TENANT, "u-1", "admin", "admin",
                "auth", "login", "", "", OperationResult.SUCCESS, "", 0)));
    }

    @Test
    void nullEntryIsIgnored() {
        assertDoesNotThrow(() -> service.record(null));
        assertTrue(repository.logs().isEmpty());
    }

    @Test
    void pageNormalizesParamsAndFiltersByTenant() {
        for (int i = 0; i < 5; i++) {
            service.record(OperationLogEntry.of(TENANT, "u-1", "admin", "admin", "user", "create",
                    "user", "u-" + i, OperationResult.SUCCESS, "", 0));
        }
        // 其他租户数据不应出现
        service.record(OperationLogEntry.of("tenant-2", "u-9", "other", "admin", "user", "create",
                "user", "x", OperationResult.SUCCESS, "", 0));

        PageResult<OperationLog> page = service.page(
                new OperationLogQuery(TENANT, "user", "create", null, 0, 0), 1, 2);

        assertEquals(5, page.total(), "只统计本租户日志");
        assertEquals(1, page.page());
        assertEquals(2, page.size());
        assertEquals(2, page.items().size(), "按每页 2 条返回");
    }

    @Test
    void pageClampsIllegalParams() {
        service.record(OperationLogEntry.of(TENANT, "u-1", "admin", "admin", "user", "create",
                "", "", OperationResult.SUCCESS, "", 0));

        // 页码 <=0 归一为 1；size<=0 用默认值
        PageResult<OperationLog> page = service.page(new OperationLogQuery(TENANT, null, null, null, 0, 0), 0, 0);
        assertEquals(1, page.page());
        assertEquals(20, page.size());

        // size 超上限被夹到 200
        PageResult<OperationLog> clamped = service.page(
                new OperationLogQuery(TENANT, null, null, null, 0, 0), 1, 9999);
        assertEquals(200, clamped.size(), "单页上限应限制为 200");
    }

    @Test
    void queryNormalizesBlankFilters() {
        OperationLogQuery query = new OperationLogQuery(TENANT, "  ", "", null, 0, 0);
        assertEquals(null, query.module(), "空白筛选应归一为「不限」");
        assertEquals(null, query.action());
    }

    @Test
    void pageReturnsEmptyWhenNoMatch() {
        PageResult<OperationLog> page = service.page(
                new OperationLogQuery(TENANT, "knowledge", null, null, 0, 0), 1, 20);
        assertEquals(0, page.total());
        assertEquals(List.of(), page.items());
    }
}
