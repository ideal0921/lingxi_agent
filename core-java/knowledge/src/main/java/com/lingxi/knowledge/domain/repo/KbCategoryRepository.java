package com.lingxi.knowledge.domain.repo;

import com.lingxi.knowledge.domain.KbCategory;

import java.util.List;
import java.util.Optional;

/**
 * 知识分类仓储端口。
 */
public interface KbCategoryRepository {

    KbCategory save(KbCategory category);

    Optional<KbCategory> findById(String categoryId);

    /** 按知识库列出分类（库内按名称排序）。 */
    List<KbCategory> listByKb(String kbId);

    /** 分类名是否已在库内存在（命名唯一性校验，库表另有唯一索引兜底）。 */
    boolean existsByKbAndName(String kbId, String name);

    void delete(String categoryId);
}
