package com.lingxi.knowledge.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * 文档切片（入库）状态机：上传后待入库，经 AI 域回写后落定为已入库或失败。
 *
 * <pre>
 * PENDING --(kb.doc.indexed: INDEXED)-->   INDEXED
 * PENDING --(kb.doc.indexed: FAILED) -->   FAILED
 * INDEXED/FAILED --(重新上传新版本 / 重试)--> PENDING
 * 任意可删状态 --(下线)-->                  DELETED（终态）
 * </pre>
 *
 * <p>终态 {@link #DELETED} 不可再迁移；非法迁移由 {@link #canTransitionTo} 拒绝，
 * 与工单状态机保持一致的「领域层单一权威」风格。</p>
 */
public enum DocumentStatus {
    PENDING,
    INDEXED,
    FAILED,
    DELETED;

    /** 目标状态是否允许从当前状态迁移。 */
    public boolean canTransitionTo(DocumentStatus target) {
        return allowedTransitions(this).contains(target);
    }

    private static Set<DocumentStatus> allowedTransitions(DocumentStatus from) {
        return switch (from) {
            case PENDING, INDEXED, FAILED ->
                    EnumSet.of(DocumentStatus.PENDING, DocumentStatus.INDEXED,
                            DocumentStatus.FAILED, DocumentStatus.DELETED);
            case DELETED -> EnumSet.noneOf(DocumentStatus.class);
        };
    }
}
