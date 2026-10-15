package com.lingxi.workflow.domain.repo;

import com.lingxi.workflow.domain.WorkflowVersion;

import java.util.List;
import java.util.Optional;

/**
 * 工作流版本快照仓储端口：版本一经发布即冻结，仅支持新增与读取（无更新方法）。
 */
public interface WorkflowVersionRepository {

    /** 新增版本快照。 */
    WorkflowVersion save(WorkflowVersion version);

    /** 按工作流列出全部版本（版本号倒序）。 */
    List<WorkflowVersion> listByWorkflow(String workflowId);

    /** 按工作流与版本号精确查询。 */
    Optional<WorkflowVersion> findByWorkflowAndVersion(String workflowId, int version);

    /** 删除工作流下的全部版本快照。 */
    void deleteByWorkflow(String workflowId);
}
