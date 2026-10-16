package com.lingxi.knowledge.application;

import com.lingxi.common.exception.BizException;
import com.lingxi.knowledge.domain.KbCategory;
import com.lingxi.knowledge.domain.KbStatus;
import com.lingxi.knowledge.domain.KnowledgeBase;
import com.lingxi.knowledge.domain.repo.KbCategoryRepository;
import com.lingxi.knowledge.domain.repo.KnowledgeBaseRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识库用例编排：知识库 CRUD（含启停）与知识分类维护。
 *
 * <p>分类命名在库内唯一，写前做存在性校验（数据库唯一索引兜底防并发）。</p>
 */
@Service
public class KnowledgeBaseService {

    private static final int ERR_KB_NOT_FOUND = 40401;
    private static final int ERR_CATEGORY_EXISTS = 40901;
    private static final int ERR_CATEGORY_NOT_FOUND = 40404;

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KbCategoryRepository categoryRepository;

    public KnowledgeBaseService(KnowledgeBaseRepository knowledgeBaseRepository,
                                KbCategoryRepository categoryRepository) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.categoryRepository = categoryRepository;
    }

    /** 新建知识库（默认启用）。 */
    public KnowledgeBase create(String tenantId, String name, String description) {
        return knowledgeBaseRepository.save(new KnowledgeBase(tenantId, name, description));
    }

    /** 按租户列出知识库。 */
    public List<KnowledgeBase> list(String tenantId) {
        return knowledgeBaseRepository.listByTenant(tenantId);
    }

    /** 查询知识库（不存在或不属于当前租户时抛业务异常）。 */
    public KnowledgeBase get(String kbId, String tenantId) {
        return requireKb(kbId, tenantId);
    }

    /** 更新名称与描述。 */
    public KnowledgeBase update(String kbId, String tenantId, String name, String description) {
        KnowledgeBase kb = requireKb(kbId, tenantId);
        kb.updateInfo(name, description);
        return knowledgeBaseRepository.save(kb);
    }

    /** 启用/停用知识库。 */
    public KnowledgeBase changeStatus(String kbId, String tenantId, KbStatus status) {
        KnowledgeBase kb = requireKb(kbId, tenantId);
        kb.changeStatus(status);
        return knowledgeBaseRepository.save(kb);
    }

    /** 新建知识分类。 */
    public KbCategory createCategory(String kbId, String tenantId, String name) {
        KnowledgeBase kb = requireKb(kbId, tenantId);
        if (categoryRepository.existsByKbAndName(kbId, name)) {
            throw new BizException(ERR_CATEGORY_EXISTS, "分类名称已存在：" + name);
        }
        return categoryRepository.save(new KbCategory(kb.getKbId(), kb.getTenantId(), name));
    }

    /** 列出知识库下的分类。 */
    public List<KbCategory> listCategories(String kbId, String tenantId) {
        requireKb(kbId, tenantId);  // 校验知识库存在且归属当前租户
        return categoryRepository.listByKb(kbId);
    }

    /** 删除分类（须归属当前租户的知识库）。 */
    public void deleteCategory(String categoryId, String tenantId) {
        KbCategory category = categoryRepository.findById(categoryId)
                .filter(item -> item.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BizException(ERR_CATEGORY_NOT_FOUND, "分类不存在"));
        categoryRepository.delete(category.getCategoryId());
    }

    /**
     * 租户内取知识库（M5-5.6 6.5 收口）：越权与不存在统一表现为「不存在」，
     * 避免通过错误差异探测其他租户资源。
     */
    private KnowledgeBase requireKb(String kbId, String tenantId) {
        KnowledgeBase kb = knowledgeBaseRepository.findById(kbId)
                .orElseThrow(() -> new BizException(ERR_KB_NOT_FOUND, "知识库不存在"));
        if (!kb.getTenantId().equals(tenantId)) {
            throw new BizException(ERR_KB_NOT_FOUND, "知识库不存在");
        }
        return kb;
    }
}
