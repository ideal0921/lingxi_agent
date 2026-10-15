package com.lingxi.workflow.infrastructure.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.lingxi.workflow.domain.Workflow;
import com.lingxi.workflow.domain.WorkflowStatus;
import com.lingxi.workflow.domain.repo.WorkflowRepository;

/**
 * MySQL 工作流存储：JdbcTemplate 直连本地 MySQL（lingxi 库 lingxi_workflow 表，启动时 schema.sql 建表）。
 * 采用 UPSERT 语义，便于同一 {@code save} 覆盖创建、草稿保存与发布计数更新。
 */
@Repository
public class MySqlWorkflowRepository implements WorkflowRepository {

    private static final String TABLE = "lingxi_workflow";

    private static final RowMapper<Workflow> ROW_MAPPER = (rs, rowNum) -> new Workflow(
            rs.getString("workflow_id"),
            rs.getString("tenant_id"),
            rs.getString("name"),
            rs.getString("description"),
            WorkflowStatus.fromValue(rs.getString("status")),
            rs.getString("draft_definition"),
            rs.getInt("latest_version"),
            rs.getInt("published_version"),
            rs.getInt("canary_version"),
            rs.getInt("canary_percent"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    private final JdbcTemplate jdbcTemplate;

    public MySqlWorkflowRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Workflow save(Workflow workflow) {
        jdbcTemplate.update("""
                INSERT INTO %s (workflow_id, tenant_id, name, description, status, draft_definition,
                                latest_version, published_version, canary_version, canary_percent,
                                created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description),
                    status = VALUES(status), draft_definition = VALUES(draft_definition),
                    latest_version = VALUES(latest_version), published_version = VALUES(published_version),
                    canary_version = VALUES(canary_version), canary_percent = VALUES(canary_percent),
                    updated_at = VALUES(updated_at)
                """.formatted(TABLE),
                workflow.getWorkflowId(), workflow.getTenantId(), workflow.getName(),
                workflow.getDescription(), workflow.getStatus().name(),
                workflow.getDraftDefinition() == null ? "" : workflow.getDraftDefinition(),
                workflow.getLatestVersion(), workflow.getPublishedVersion(),
                workflow.getCanaryVersion(), workflow.getCanaryPercent(),
                workflow.getCreatedAtMs(), workflow.getUpdatedAtMs());
        return workflow;
    }

    @Override
    public Optional<Workflow> findById(String workflowId) {
        List<Workflow> result = jdbcTemplate.query(
                "SELECT * FROM " + TABLE + " WHERE workflow_id = ?", ROW_MAPPER, workflowId);
        return result.stream().findFirst();
    }

    @Override
    public List<Workflow> listByTenant(String tenantId) {
        return jdbcTemplate.query(
                "SELECT * FROM " + TABLE + " WHERE tenant_id = ? ORDER BY created_at DESC",
                ROW_MAPPER, tenantId);
    }

    @Override
    public void delete(String workflowId) {
        jdbcTemplate.update("DELETE FROM " + TABLE + " WHERE workflow_id = ?", workflowId);
    }
}
