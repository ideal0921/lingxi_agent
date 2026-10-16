package com.lingxi.common.tenant;

/**
 * 数据隔离审计端口（M5-5.5，依赖倒置）：
 * 业务域在**拒绝跨租户访问**时上报审计事实，实现方可落日志/消息队列。
 *
 * <p>约束：审计上报失败不得影响主链路（调用方需保证），实现方自行兜底异常。</p>
 */
public interface TenantIsolationAuditor {

    /** 记录一次跨租户越权拒绝。 */
    void record(IsolationViolation violation);
}
