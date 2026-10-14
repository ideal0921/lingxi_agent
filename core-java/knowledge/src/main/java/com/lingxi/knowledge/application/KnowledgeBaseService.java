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

    /** 查询知识库（不存在抛业务异常）。 */
    public KnowledgeBase get(String kbId) {
        return knowledgeBaseRepository.findById(kbId)
                .orElseThrow(() -> new BizException(ERR_KB_NOT_FOUND, "知识库不存在"));
    }

    /** 更新名称与描述。 */
    public KnowledgeBase update(String kbId, String name, String description) {
        KnowledgeBase kb = get(kbId);
        kb.updateInfo(name, description);
        return knowledgeBaseRepository.save(kb);
    }

    /** 启用/停用知识库。 */
    public KnowledgeBase changeStatus(String kbId, KbStatus status) {
        KnowledgeBase kb = get(kbId);
        kb.changeStatus(status);
        return knowledgeBaseRepository.save(kb);
    }

    /** 新建知识分类。 */
    public KbCategory createCategory(String kbId, String name) {
        KnowledgeBase kb = get(kbId);
        if (categoryRepository.existsByKbAndName(kbId, name)) {
            throw new BizException(ERR_CATEGORY_EXISTS, "分类名称已存在：" + name);
        }
        return categoryRepository.save(new KbCategory(kb.getKbId(), kb.getTenantId(), name));
    }

    /** 列出知识库下的分类。 */
    public List<KbCategory> listCategories(String kbId) {
        get(kbId);  // 校验知识库存在
        return categoryRepository.listByKb(kbId);
    }

    /** 删除分类。 */
    public void deleteCategory(String categoryId) {
        categoryRepository.delete(categoryId);
    }
}
