package com.lingxi.workflow.application;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.lingxi.common.exception.BizException;
import com.lingxi.workflow.domain.Workflow;
import com.lingxi.workflow.domain.WorkflowGraph;
import com.lingxi.workflow.domain.WorkflowGraphValidator;
import com.lingxi.workflow.domain.WorkflowVersion;
import com.lingxi.workflow.domain.repo.WorkflowRepository;
import com.lingxi.workflow.domain.repo.WorkflowVersionRepository;

/**
 * 编排域用例编排：工作流 CRUD、草稿保存、版本发布与回滚。
 *
 * <p>校验强度分两级：保存草稿只校验「结构可解析」，避免编辑过程中被业务规则卡死；
 * 发布时执行 {@link WorkflowGraphValidator} 全量校验，不通过则拒绝并保持草稿与版本均不落库。</p>
 *
 * <p>回滚语义：把历史版本内容复制为当前草稿，不新增版本、不改生效版本，
 * 运营复核后再发布即生成新版本号——历史完整、可审计。</p>
 */
@Service
public class WorkflowService {

    private static final int ERR_WORKFLOW_NOT_FOUND = 40402;
    private static final int ERR_DRAFT_INVALID = 40005;
    private static final int ERR_PUBLISH_REJECTED = 40101;
    private static final int ERR_ROLLOUT_REJECTED = 40102;
    private static final int ERR_VERSION_NOT_FOUND = 40403;

    private static final Logger logger = LoggerFactory.getLogger(WorkflowService.class);

    private final WorkflowRepository workflowRepository;
    private final WorkflowVersionRepository versionRepository;
    /** 图定义 JSON 字节上限（防止超大 payload 落库与拖垮校验）。 */
    private final int definitionMaxBytes;

    public WorkflowService(WorkflowRepository workflowRepository,
                           WorkflowVersionRepository versionRepository,
                           @Value("${lingxi.workflow.definition-max-bytes:262144}") int definitionMaxBytes) {
        this.workflowRepository = workflowRepository;
        this.versionRepository = versionRepository;
        this.definitionMaxBytes = definitionMaxBytes;
    }

    /** 按租户列出工作流（不含草稿正文，由仓储返回的对象统一为空草稿语义）。 */
    public List<Workflow> list(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new BizException(ERR_WORKFLOW_NOT_FOUND, "租户 ID 不能为空");
        }
        return workflowRepository.listByTenant(tenantId);
    }

    /** 新建工作流（启用、空草稿、未发布）。 */
    public Workflow create(String tenantId, String name, String description) {
        return workflowRepository.save(new Workflow(tenantId, name, description));
    }

    /** 查询工作流详情。 */
    public Workflow get(String workflowId) {
        return workflowRepository.findById(workflowId)
                .orElseThrow(() -> new BizException(ERR_WORKFLOW_NOT_FOUND, "工作流不存在"));
    }

    /** 保存草稿：仅校验图定义可解析（结构级），不执行业务规则。 */
    public Workflow saveDraft(String workflowId, String definitionJson) {
        Workflow workflow = get(workflowId);
        try {
            WorkflowGraph.parse(definitionJson, definitionMaxBytes);
        } catch (IllegalArgumentException e) {
            throw new BizException(ERR_DRAFT_INVALID, e.getMessage());
        }
        workflow.saveDraft(definitionJson);
        return workflowRepository.save(workflow);
    }

    /**
     * 发布：对当前草稿全量校验，通过则冻结为不可变版本快照。
     *
     * @param canary 灰度发布（M4-4.8）：为 {@code true} 时只推进最新版本号，
     *               生效版本保持不变，随后由 {@link #setRollout} 指定分流比例
     * @return 新版本号
     */
    public int publish(String workflowId, String note, String operator, boolean canary) {
        Workflow workflow = get(workflowId);
        if (!workflow.hasDraft()) {
            throw new BizException(ERR_PUBLISH_REJECTED, "草稿为空，无可发布内容");
        }
        WorkflowGraph graph;
        try {
            graph = WorkflowGraph.parse(workflow.getDraftDefinition(), definitionMaxBytes);
        } catch (IllegalArgumentException e) {
            throw new BizException(ERR_PUBLISH_REJECTED, e.getMessage());
        }
        WorkflowGraphValidator.validate(graph)
                .ifPresent(reason -> {
                    throw new BizException(ERR_PUBLISH_REJECTED, reason);
                });
        int version = workflow.getLatestVersion() + 1;
        versionRepository.save(new WorkflowVersion(workflowId, workflow.getTenantId(), version,
                workflow.getDraftDefinition(), note, operator));
        workflow.markPublished(version, canary);
        return workflowRepository.save(workflow).getLatestVersion();
    }

    /**
     * 设置灰度分流（M4-4.8）：{@code canaryPercent=0} 表示关闭灰度并把灰度版本归零。
     *
     * <p>灰度版本必须是**已存在的版本快照**且不超过最新版本号——避免灰度指向一个
     * 不存在的图，导致运行时取不到定义。</p>
     */
    public Workflow setRollout(String workflowId, int canaryVersion, int canaryPercent, String operator) {
        Workflow workflow = get(workflowId);
        if (canaryPercent > 0) {
            if (canaryVersion > workflow.getLatestVersion()) {
                throw new BizException(ERR_ROLLOUT_REJECTED,
                        "灰度版本号超出最新版本：" + canaryVersion + " > " + workflow.getLatestVersion());
            }
            versionRepository.findByWorkflowAndVersion(workflowId, canaryVersion)
                    .orElseThrow(() -> new BizException(ERR_ROLLOUT_REJECTED, "灰度版本不存在：" + canaryVersion));
        }
        try {
            workflow.setRollout(canaryVersion, canaryPercent);
        } catch (IllegalArgumentException e) {
            throw new BizException(ERR_ROLLOUT_REJECTED, e.getMessage());
        }
        logger.info("设置灰度分流 workflowId={} canaryVersion={} percent={} operator={}",
                workflowId, workflow.getCanaryVersion(), workflow.getCanaryPercent(), operator);
        return workflowRepository.save(workflow);
    }

    /** 列出版本快照（版本号倒序）。 */
    public List<WorkflowVersion> listVersions(String workflowId) {
        get(workflowId);  // 校验工作流存在
        return versionRepository.listByWorkflow(workflowId);
    }

    /** 查询某版本快照（只读，不影响草稿）。 */
    public WorkflowVersion getVersion(String workflowId, int version) {
        return versionRepository.findByWorkflowAndVersion(workflowId, version)
                .orElseThrow(() -> new BizException(ERR_VERSION_NOT_FOUND, "版本不存在：" + version));
    }

    /** 回滚：把指定版本内容复制为当前草稿（不新增版本、不改生效版本）。 */
    public Workflow rollback(String workflowId, int version, String operator) {
        Workflow workflow = get(workflowId);
        WorkflowVersion snapshot = getVersion(workflowId, version);
        workflow.saveDraft(snapshot.getDefinition());
        return workflowRepository.save(workflow);
    }

    /** 删除工作流（连带清除版本快照）。 */
    public void delete(String workflowId) {
        versionRepository.deleteByWorkflow(workflowId);
        workflowRepository.delete(workflowId);
    }
}
