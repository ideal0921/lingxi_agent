package com.lingxi.workflow.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lingxi.common.exception.BizException;
import com.lingxi.workflow.domain.Workflow;
import com.lingxi.workflow.domain.WorkflowVersion;
import com.lingxi.workflow.domain.repo.WorkflowRepository;
import com.lingxi.workflow.domain.repo.WorkflowVersionRepository;

/**
 * 编排域用例编排测试（全内存替身，不连 MySQL）。
 *
 * <p>覆盖：新建、草稿保存（合法/非法）、发布成功与版本递增、发布被拒（校验不过时不落版本）、
 * 空草稿发布、版本列表倒序、回滚只改草稿、删除连带清版本。</p>
 */
class WorkflowServiceTest {

    private static final String TENANT = "t-studio";
    private static final int MAX_BYTES = 262144;

    private static final String VALID_GRAPH = """
            {"nodes":[{"id":"n1","type":"retrieve","config":{"kb_id":"kb-1"}},
                      {"id":"n2","type":"llm","config":{"temperature":0.7}}],
             "edges":[{"id":"e1","source":"n1","target":"n2"}]}
            """;

    /** 非法图：检索节点缺 kb_id，发布应被拒。 */
    private static final String INVALID_GRAPH = """
            {"nodes":[{"id":"n1","type":"retrieve"},{"id":"n2","type":"llm"}],
             "edges":[{"id":"e1","source":"n1","target":"n2"}]}
            """;

    private InMemoryWorkflowRepository workflowRepository;
    private InMemoryWorkflowVersionRepository versionRepository;
    private WorkflowService service;

    @BeforeEach
    void setUp() {
        workflowRepository = new InMemoryWorkflowRepository();
        versionRepository = new InMemoryWorkflowVersionRepository();
        service = new WorkflowService(workflowRepository, versionRepository, MAX_BYTES);
    }

    @Test
    void createStartsEnabledWithNoDraftAndNoVersion() {
        Workflow workflow = service.create(TENANT, "售后退款编排", "退款场景");

        assertEquals(TENANT, workflow.getTenantId());
        assertEquals(0, workflow.getLatestVersion());
        assertEquals(0, workflow.getPublishedVersion());
        assertTrue(workflow.getDraftDefinition().isEmpty());
        assertTrue(service.list(TENANT).stream()
                .anyMatch(item -> item.getWorkflowId().equals(workflow.getWorkflowId())));
    }

    @Test
    void saveDraftPersistsValidGraph() {
        Workflow workflow = service.create(TENANT, "草稿", "");

        service.saveDraft(workflow.getWorkflowId(), VALID_GRAPH);

        assertEquals(VALID_GRAPH, service.get(workflow.getWorkflowId()).getDraftDefinition());
    }

    @Test
    void saveDraftRejectsUnparsableGraph() {
        Workflow workflow = service.create(TENANT, "草稿", "");

        BizException error = assertThrows(BizException.class,
                () -> service.saveDraft(workflow.getWorkflowId(), "{not json"));

        assertEquals(40005, error.getCode());
        assertTrue(service.get(workflow.getWorkflowId()).getDraftDefinition().isEmpty());
    }

    @Test
    void publishFreezesVersionAndAdvancesCounters() {
        Workflow workflow = service.create(TENANT, "发布", "");
        service.saveDraft(workflow.getWorkflowId(), VALID_GRAPH);

        int first = service.publish(workflow.getWorkflowId(), "首版", "ops", false);

        assertEquals(1, first);
        Workflow published = service.get(workflow.getWorkflowId());
        assertEquals(1, published.getLatestVersion());
        assertEquals(1, published.getPublishedVersion());
        // 发布后草稿保留，便于继续迭代
        assertEquals(VALID_GRAPH, published.getDraftDefinition());
        assertEquals(VALID_GRAPH, service.getVersion(workflow.getWorkflowId(), 1).getDefinition());
    }

    @Test
    void publishTwiceIncreasesVersion() {
        Workflow workflow = service.create(TENANT, "发布", "");
        service.saveDraft(workflow.getWorkflowId(), VALID_GRAPH);
        service.publish(workflow.getWorkflowId(), "v1", "ops", false);

        service.saveDraft(workflow.getWorkflowId(), VALID_GRAPH.replace("kb-1", "kb-2"));
        int second = service.publish(workflow.getWorkflowId(), "v2", "ops", false);

        assertEquals(2, second);
        assertEquals(2, service.get(workflow.getWorkflowId()).getPublishedVersion());
        assertEquals(2, service.listVersions(workflow.getWorkflowId()).size());
    }

    @Test
    void publishRejectedByValidationKeepsDraftAndCreatesNoVersion() {
        Workflow workflow = service.create(TENANT, "发布", "");
        service.saveDraft(workflow.getWorkflowId(), INVALID_GRAPH);

        BizException error = assertThrows(BizException.class,
                () -> service.publish(workflow.getWorkflowId(), "不该成功", "ops", false));

        assertEquals(40101, error.getCode());
        assertTrue(error.getMessage().contains("kb_id"));
        assertEquals(0, service.get(workflow.getWorkflowId()).getLatestVersion());
        assertTrue(service.listVersions(workflow.getWorkflowId()).isEmpty());
        assertEquals(INVALID_GRAPH, service.get(workflow.getWorkflowId()).getDraftDefinition());
    }

    @Test
    void publishRejectedWhenDraftEmpty() {
        Workflow workflow = service.create(TENANT, "空草稿", "");

        BizException error = assertThrows(BizException.class,
                () -> service.publish(workflow.getWorkflowId(), "n", "ops", false));

        assertEquals(40101, error.getCode());
        assertTrue(error.getMessage().contains("草稿为空"));
    }

    @Test
    void listVersionsReturnsDescendingOrder() {
        Workflow workflow = service.create(TENANT, "多版本", "");
        service.saveDraft(workflow.getWorkflowId(), VALID_GRAPH);
        service.publish(workflow.getWorkflowId(), "v1", "ops", false);
        service.saveDraft(workflow.getWorkflowId(), VALID_GRAPH.replace("kb-1", "kb-2"));
        service.publish(workflow.getWorkflowId(), "v2", "ops", false);

        List<WorkflowVersion> versions = service.listVersions(workflow.getWorkflowId());

        assertEquals(List.of(2, 1), versions.stream().map(WorkflowVersion::getVersion).toList());
    }

    @Test
    void getVersionRejectsUnknownVersion() {
        Workflow workflow = service.create(TENANT, "无此版本", "");

        BizException error = assertThrows(BizException.class,
                () -> service.getVersion(workflow.getWorkflowId(), 7));

        assertEquals(40403, error.getCode());
    }

    @Test
    void rollbackCopiesSnapshotIntoDraftWithoutTouchingVersions() {
        Workflow workflow = service.create(TENANT, "回滚", "");
        service.saveDraft(workflow.getWorkflowId(), VALID_GRAPH);
        service.publish(workflow.getWorkflowId(), "v1", "ops", false);
        service.saveDraft(workflow.getWorkflowId(), VALID_GRAPH.replace("kb-1", "kb-2"));
        service.publish(workflow.getWorkflowId(), "v2", "ops", false);

        Workflow rolledBack = service.rollback(workflow.getWorkflowId(), 1, "ops");

        assertEquals(VALID_GRAPH, rolledBack.getDraftDefinition());
        // 回滚不动版本与生效版本：历史完整，重新发布才产生新版本号
        assertEquals(2, rolledBack.getLatestVersion());
        assertEquals(2, rolledBack.getPublishedVersion());
        assertEquals(2, service.listVersions(workflow.getWorkflowId()).size());
    }

    @Test
    void getRejectsUnknownWorkflow() {
        BizException error = assertThrows(BizException.class, () -> service.get("no-such-id"));
        assertEquals(40402, error.getCode());
    }

    @Test
    void deleteRemovesWorkflowAndVersions() {
        Workflow workflow = service.create(TENANT, "删除", "");
        service.saveDraft(workflow.getWorkflowId(), VALID_GRAPH);
        service.publish(workflow.getWorkflowId(), "v1", "ops", false);

        service.delete(workflow.getWorkflowId());

        assertThrows(BizException.class, () -> service.get(workflow.getWorkflowId()));
        assertTrue(versionRepository.listByWorkflow(workflow.getWorkflowId()).isEmpty());
    }

    // ---------- 灰度发布与分流（M4-4.8） ----------

    @Test
    void canaryPublishAdvancesLatestOnly() {
        Workflow workflow = service.create(TENANT, "灰度发布", "");
        service.saveDraft(workflow.getWorkflowId(), VALID_GRAPH);
        service.publish(workflow.getWorkflowId(), "v1", "ops", false);

        service.saveDraft(workflow.getWorkflowId(), VALID_GRAPH.replace("kb-1", "kb-2"));
        int version = service.publish(workflow.getWorkflowId(), "v2 灰度", "ops", true);

        assertEquals(2, version);
        Workflow after = service.get(workflow.getWorkflowId());
        // 关键语义：灰度发布只推进最新版本号，生效版本保持不变（「已发布但未生效」）
        assertEquals(2, after.getLatestVersion());
        assertEquals(1, after.getPublishedVersion());
        // 版本快照仍正常冻结
        assertEquals(2, service.listVersions(workflow.getWorkflowId()).size());
    }

    @Test
    void setRolloutEnablesCanaryOnExistingVersion() {
        Workflow workflow = service.create(TENANT, "开灰度", "");
        service.saveDraft(workflow.getWorkflowId(), VALID_GRAPH);
        service.publish(workflow.getWorkflowId(), "v1", "ops", false);
        service.saveDraft(workflow.getWorkflowId(), VALID_GRAPH.replace("kb-1", "kb-2"));
        service.publish(workflow.getWorkflowId(), "v2 灰度", "ops", true);

        Workflow rolled = service.setRollout(workflow.getWorkflowId(), 2, 30, "ops");

        assertEquals(2, rolled.getCanaryVersion());
        assertEquals(30, rolled.getCanaryPercent());
        assertTrue(rolled.hasRollout());
        // 持久化后仍可见（读路径一致）
        assertEquals(2, service.get(workflow.getWorkflowId()).getCanaryVersion());
    }

    @Test
    void setRolloutZeroPercentClosesCanary() {
        Workflow workflow = service.create(TENANT, "关灰度", "");
        service.saveDraft(workflow.getWorkflowId(), VALID_GRAPH);
        service.publish(workflow.getWorkflowId(), "v1", "ops", false);
        service.setRollout(workflow.getWorkflowId(), 1, 50, "ops");

        Workflow closed = service.setRollout(workflow.getWorkflowId(), 0, 0, "ops");

        assertEquals(0, closed.getCanaryVersion());
        assertEquals(0, closed.getCanaryPercent());
        assertFalse(closed.hasRollout());
    }

    @Test
    void setRolloutRejectsUnknownOrFutureVersion() {
        Workflow workflow = service.create(TENANT, "非法灰度", "");
        service.saveDraft(workflow.getWorkflowId(), VALID_GRAPH);
        service.publish(workflow.getWorkflowId(), "v1", "ops", false);

        BizException tooNew = assertThrows(BizException.class,
                () -> service.setRollout(workflow.getWorkflowId(), 5, 50, "ops"));
        assertEquals(40102, tooNew.getCode());
        assertTrue(tooNew.getMessage().contains("超出最新版本"));

        // 数据不一致场景（版本快照缺失）：直接移除 v1 快照，灰度应被拒而不是落到一个取不到的版本
        versionRepository.deleteByWorkflow(workflow.getWorkflowId());
        BizException missing = assertThrows(BizException.class,
                () -> service.setRollout(workflow.getWorkflowId(), 1, 50, "ops"));
        assertEquals(40102, missing.getCode());
        assertTrue(missing.getMessage().contains("灰度版本不存在"));
        assertEquals(0, service.get(workflow.getWorkflowId()).getCanaryPercent());
    }

    @Test
    void setRolloutRejectsPercentOutOfRange() {
        Workflow workflow = service.create(TENANT, "比例越界", "");
        service.saveDraft(workflow.getWorkflowId(), VALID_GRAPH);
        service.publish(workflow.getWorkflowId(), "v1", "ops", false);

        BizException error = assertThrows(BizException.class,
                () -> service.setRollout(workflow.getWorkflowId(), 1, 150, "ops"));

        assertEquals(40102, error.getCode());
        assertEquals(0, service.get(workflow.getWorkflowId()).getCanaryPercent());
    }

    @Test
    void setRolloutIsIdempotentAndKeepsVersionOnRepeat() {
        Workflow workflow = service.create(TENANT, "幂等灰度", "");
        service.saveDraft(workflow.getWorkflowId(), VALID_GRAPH);
        service.publish(workflow.getWorkflowId(), "v1", "ops", false);

        service.setRollout(workflow.getWorkflowId(), 1, 20, "ops");
        Workflow second = service.setRollout(workflow.getWorkflowId(), 1, 20, "ops");

        assertEquals(1, second.getCanaryVersion());
        assertEquals(20, second.getCanaryPercent());
        assertEquals(1, service.listVersions(workflow.getWorkflowId()).size());
    }

    // ---------- 内存替身 ----------

    /** 内存工作流仓储：语义与 MySQL UPSERT 实现对齐。 */
    private static final class InMemoryWorkflowRepository implements WorkflowRepository {

        private final Map<String, Workflow> store = new LinkedHashMap<>();

        @Override
        public Workflow save(Workflow workflow) {
            store.put(workflow.getWorkflowId(), workflow);
            return workflow;
        }

        @Override
        public Optional<Workflow> findById(String workflowId) {
            return Optional.ofNullable(store.get(workflowId));
        }

        @Override
        public List<Workflow> listByTenant(String tenantId) {
            return store.values().stream()
                    .filter(workflow -> tenantId.equals(workflow.getTenantId()))
                    .sorted(Comparator.comparingLong(Workflow::getCreatedAtMs).reversed())
                    .toList();
        }

        @Override
        public void delete(String workflowId) {
            store.remove(workflowId);
        }
    }

    /** 内存版本快照仓储：只增不改，语义与 MySQL 实现对齐。 */
    private static final class InMemoryWorkflowVersionRepository implements WorkflowVersionRepository {

        private final List<WorkflowVersion> store = new ArrayList<>();
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public WorkflowVersion save(WorkflowVersion version) {
            store.add(version);
            sequence.incrementAndGet();
            return version;
        }

        @Override
        public List<WorkflowVersion> listByWorkflow(String workflowId) {
            return store.stream()
                    .filter(version -> workflowId.equals(version.getWorkflowId()))
                    .sorted(Comparator.comparingInt(WorkflowVersion::getVersion).reversed())
                    .toList();
        }

        @Override
        public Optional<WorkflowVersion> findByWorkflowAndVersion(String workflowId, int version) {
            return store.stream()
                    .filter(item -> workflowId.equals(item.getWorkflowId()) && item.getVersion() == version)
                    .findFirst();
        }

        @Override
        public void deleteByWorkflow(String workflowId) {
            store.removeIf(version -> workflowId.equals(version.getWorkflowId()));
        }
    }
}
