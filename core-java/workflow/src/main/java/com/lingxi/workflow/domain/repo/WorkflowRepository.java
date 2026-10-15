package com.lingxi.workflow.domain.repo;

import com.lingxi.workflow.domain.Workflow;

import java.util.List;
import java.util.Optional;

/**
 * 工作流仓储端口（实现置于 infrastructure，领域层不依赖持久化技术）。
 */
public interface WorkflowRepository {

    /** 保存（UPSERT：同一 {@code save} 覆盖创建与草稿/版本计数更新）。 */
    Workflow save(Workflow workflow);

    /** 按 ID 查询。 */
    Optional<Workflow> findById(String workflowId);

    /** 按租户列出（创建时间倒序）。 */
    List<Workflow> listByTenant(String tenantId);

    /** 删除工作流主体（版本快照由版本仓储另行清除）。 */
    void delete(String workflowId);
}
