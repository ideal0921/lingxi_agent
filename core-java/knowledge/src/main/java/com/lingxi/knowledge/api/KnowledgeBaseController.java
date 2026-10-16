package com.lingxi.knowledge.api;

import com.lingxi.common.exception.BizException;
import com.lingxi.common.result.Result;
import com.lingxi.common.security.JwtClaims;
import com.lingxi.common.security.RequireRole;
import com.lingxi.common.tenant.TenantContext;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识库与分类管理接口（管理后台，经 Go 网关 `/api/v1/kb/**` 转发）。
 *
 * <p>M5-5.6 6.5 租户收口：租户一律取自**可信租户上下文**（JWT 解析结果），
 * 不再接受前端传入的 tenant_id；按 ID 操作一律做归属校验（越权对外表现为「不存在」）。
 * 权限：仅 `admin`（网关与 Java 双层校验）。</p>
 */
@RestController
@RequestMapping("/api/v1/kb/bases")
@RequireRole({JwtClaims.ROLE_ADMIN})
public class KnowledgeBaseController {

    private static final int ERR_BAD_STATUS = 40004;

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    /** 新建知识库（租户取可信上下文）。 */
    @PostMapping
    public Result<KbView> create(@Valid @RequestBody CreateKbRequest request) {
        return Result.ok(KbView.from(knowledgeBaseService.create(
                TenantContext.requireTenantId(), request.name(), request.description())));
    }

    /** 列出当前租户的知识库。 */
    @GetMapping
    public Result<List<KbView>> list() {
        return Result.ok(knowledgeBaseService.list(TenantContext.requireTenantId())
                .stream().map(KbView::from).toList());
    }

    /** 查询知识库详情。 */
    @GetMapping("/{kbId}")
    public Result<KbView> get(@PathVariable String kbId) {
        return Result.ok(KbView.from(
                knowledgeBaseService.get(kbId, TenantContext.requireTenantId())));
    }

    /** 更新知识库名称与描述。 */
    @PutMapping("/{kbId}")
    public Result<KbView> update(@PathVariable String kbId, @Valid @RequestBody UpdateKbRequest request) {
        return Result.ok(KbView.from(knowledgeBaseService.update(
                kbId, TenantContext.requireTenantId(), request.name(), request.description())));
    }

    /** 启用/停用知识库。 */
    @PostMapping("/{kbId}/status")
    public Result<KbView> changeStatus(@PathVariable String kbId,
                                       @Valid @RequestBody ChangeStatusRequest request) {
        return Result.ok(KbView.from(knowledgeBaseService.changeStatus(
                kbId, TenantContext.requireTenantId(), parseStatus(request.status()))));
    }

    /** 新建知识分类。 */
    @PostMapping("/{kbId}/categories")
    public Result<CategoryView> createCategory(@PathVariable String kbId,
                                               @Valid @RequestBody CreateCategoryRequest request) {
        return Result.ok(CategoryView.from(knowledgeBaseService.createCategory(
                kbId, TenantContext.requireTenantId(), request.name())));
    }

    /** 列出知识库下的分类。 */
    @GetMapping("/{kbId}/categories")
    public Result<List<CategoryView>> listCategories(@PathVariable String kbId) {
        return Result.ok(knowledgeBaseService.listCategories(kbId, TenantContext.requireTenantId())
                .stream().map(CategoryView::from).toList());
    }

    /** 删除分类。 */
    @DeleteMapping("/categories/{categoryId}")
    public Result<Void> deleteCategory(@PathVariable String categoryId) {
        knowledgeBaseService.deleteCategory(categoryId, TenantContext.requireTenantId());
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
