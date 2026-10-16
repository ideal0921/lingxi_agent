package com.lingxi.log.domain.repo;

import java.util.List;

import com.lingxi.log.domain.OperationLog;
import com.lingxi.log.application.OperationLogQuery;

/**
 * 操作日志存储抽象（M5-5.6）：默认 MySQL 实现，内存实现供单元测试。
 */
public interface OperationLogRepository {

    /** 追加一条日志（只追加，不更新）。 */
    void append(OperationLog log);

    /** 分页查询：租户内过滤，支持模块/动作/结果/时间区间。 */
    List<OperationLog> list(OperationLogQuery query, int offset, int limit);

    /** 命中总数（分页器用）。 */
    long count(OperationLogQuery query);
}
