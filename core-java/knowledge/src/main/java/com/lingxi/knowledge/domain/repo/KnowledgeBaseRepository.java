package com.lingxi.knowledge.domain.repo;

import com.lingxi.knowledge.domain.KnowledgeBase;

import java.util.List;
import java.util.Optional;

/**
 * 知识库仓储端口（依赖倒置：领域层只声明需求，实现位于 infrastructure）。
 */
public interface KnowledgeBaseRepository {

    KnowledgeBase save(KnowledgeBase knowledgeBase);

    Optional<KnowledgeBase> findById(String kbId);

    /** 按租户列出知识库（运营后台与检索选库）。 */
    List<KnowledgeBase> listByTenant(String tenantId);

    /** 删除知识库（逻辑删除由应用层决定，这里为物理删除）。 */
    void delete(String kbId);
}
