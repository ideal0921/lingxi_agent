package com.lingxi.report.domain.repo;

import com.lingxi.report.domain.AnalyticsEvent;

/**
 * 分析明细仓储端口（M5-5.1）：依赖倒置，本期由 MySQL 实现。
 *
 * <p>只提供「幂等插入」——Kafka 至少一次语义下同一事件会被重投，
 * 实现须以 {@code event_id} 为主键（或唯一键）忽略重复，**返回是否为新事件**；
 * 应用层据此决定是否更新聚合，避免数值虚高。</p>
 */
public interface AnalyticsEventRepository {

    /**
     * 幂等写入明细。
     *
     * @param event 分析事件
     * @return {@code true} 表示本次为新事件（应继续更新聚合）；{@code false} 表示重复投递（跳过聚合）
     */
    boolean insertIgnore(AnalyticsEvent event);
}
