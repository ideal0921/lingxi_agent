package com.lingxi.knowledge.api;

import com.lingxi.common.exception.BizException;
import com.lingxi.common.result.Result;
import com.lingxi.knowledge.api.dto.KnowledgeDtos.CategoryView;
import com.lingxi.knowledge.api.dto.KnowledgeDtos.ChangeStatusRequest;
import com.lingxi.knowledge.api.dto.KnowledgeDtos.CreateCategoryRequest;
import com.lingxi.knowledge.api.dto.KnowledgeDtos.CreateKbRequest;
import com.lingxi.knowledge.api.dto.KnowledgeDtos.KbView;
import com.lingxi.knowledge.api.dto.KnowledgeDtos.UpdateKbRequest;
import com.lingxi.knowledge.application.KnowledgeBaseService;
import com.lingxi.knowledge.domain.KbStatus;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识库与分类管理接口（管理后台直连本域）。
 *
 * <p>统一返回 {@link Result}，业务异常由 {@code GlobalExceptionHandler} 转换。</p>
 */
@RestController
@RequestMapping("/api/v1/kb/bases")
public class KnowledgeBaseController {

    private static final int ERR_BAD_STATUS = 40004;

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    /** 新建知识库。 */
    @PostMapping
    public Result<KbView> create(@Valid @RequestBody CreateKbRequest request) {
        return Result.ok(KbView.from(
                knowledgeBaseService.create(request.tenantId(), request.name(), request.description())));
    }

    /** 按租户列出知识库。 */
    @GetMapping
    public Result<List<KbView>> list(@RequestParam String tenantId) {
        return Result.ok(knowledgeBaseService.list(tenantId).stream().map(KbView::from).toList());
    }

    /** 查询知识库详情。 */
    @GetMapping("/{kbId}")
    public Result<KbView> get(@PathVariable String kbId) {
        return Result.ok(KbView.from(knowledgeBaseService.get(kbId)));
    }

    /** 更新知识库名称与描述。 */
    @PutMapping("/{kbId}")
    public Result<KbView> update(@PathVariable String kbId, @Valid @RequestBody UpdateKbRequest request) {
        return Result.ok(KbView.from(
                knowledgeBaseService.update(kbId, request.name(), request.description())));
    }

    /** 启用/停用知识库。 */
    @PostMapping("/{kbId}/status")
    public Result<KbView> changeStatus(@PathVariable String kbId,
                                       @Valid @RequestBody ChangeStatusRequest request) {
        return Result.ok(KbView.from(
                knowledgeBaseService.changeStatus(kbId, parseStatus(request.status()))));
    }

    /** 新建知识分类。 */
    @PostMapping("/{kbId}/categories")
    public Result<CategoryView> createCategory(@PathVariable String kbId,
                                               @Valid @RequestBody CreateCategoryRequest request) {
        return Result.ok(CategoryView.from(
                knowledgeBaseService.createCategory(kbId, request.name())));
    }

    /** 列出知识库下的分类。 */
    @GetMapping("/{kbId}/categories")
    public Result<List<CategoryView>> listCategories(@PathVariable String kbId) {
        return Result.ok(knowledgeBaseService.listCategories(kbId).stream().map(CategoryView::from).toList());
    }

    /** 删除分类。 */
    @DeleteMapping("/categories/{categoryId}")
    public Result<Void> deleteCategory(@PathVariable String categoryId) {
        knowledgeBaseService.deleteCategory(categoryId);
        return Result.ok();
    }

    private static KbStatus parseStatus(String status) {
        try {
            return KbStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException(ERR_BAD_STATUS, "非法的知识库状态：" + status);
        }
    }
}
