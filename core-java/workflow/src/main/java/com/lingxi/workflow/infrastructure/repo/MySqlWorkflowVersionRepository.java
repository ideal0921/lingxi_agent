package com.lingxi.workflow.infrastructure.repo;

import com.lingxi.workflow.domain.WorkflowVersion;
import com.lingxi.workflow.domain.repo.WorkflowVersionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * MySQL 工作流版本快照存储：版本发布即写入且不可变，故只有 INSERT，没有 UPDATE。
 * (workflow_id, version) 唯一索引是并发重复发布的最后兜底。
 */
@Repository
public class MySqlWorkflowVersionRepository implements WorkflowVersionRepository {

    private static final String TABLE = "lingxi_workflow_version";

    private static final RowMapper<WorkflowVersion> ROW_MAPPER = (rs, rowNum) -> new WorkflowVersion(
            rs.getString("version_id"),
            rs.getString("workflow_id"),
            rs.getString("tenant_id"),
            rs.getInt("version"),
            rs.getString("definition"),
            rs.getString("note"),
            rs.getString("operator"),
            rs.getLong("published_at"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbcTemplate;

    public MySqlWorkflowVersionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public WorkflowVersion save(WorkflowVersion version) {
        jdbcTemplate.update("""
                INSERT INTO %s (version_id, workflow_id, tenant_id, version, definition,
                                note, operator, published_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.formatted(TABLE),
                version.getVersionId(), version.getWorkflowId(), version.getTenantId(),
                version.getVersion(), version.getDefinition(), version.getNote(),
                version.getOperator(), version.getPublishedAtMs(), version.getCreatedAtMs());
        return version;
    }

    @Override
    public List<WorkflowVersion> listByWorkflow(String workflowId) {
        return jdbcTemplate.query(
                "SELECT * FROM " + TABLE + " WHERE workflow_id = ? ORDER BY version DESC",
                ROW_MAPPER, workflowId);
    }

    @Override
    public Optional<WorkflowVersion> findByWorkflowAndVersion(String workflowId, int version) {
        List<WorkflowVersion> result = jdbcTemplate.query(
                "SELECT * FROM " + TABLE + " WHERE workflow_id = ? AND version = ?",
                ROW_MAPPER, workflowId, version);
        return result.stream().findFirst();
    }

    @Override
    public void deleteByWorkflow(String workflowId) {
        jdbcTemplate.update("DELETE FROM " + TABLE + " WHERE workflow_id = ?", workflowId);
    }
}
