package com.lingxi.trace.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.lingxi.common.result.PageResult;
import com.lingxi.common.result.Result;
import com.lingxi.common.security.JwtClaims;
import com.lingxi.common.security.RequireRole;
import com.lingxi.common.tenant.TenantContext;
import com.lingxi.trace.api.dto.ApiTraceSpanView;
import com.lingxi.trace.application.ApiTraceQuery;
import com.lingxi.trace.application.ApiTraceService;
import com.lingxi.trace.domain.ApiTraceSpan;

/**
 * 链路追踪查询接口（M5-5.9）：管理后台「接口链路追踪」页数据源。
 *
 * <p>权限：仅 `admin`；租户范围取可信租户上下文（不可由前端指定其他租户）。</p>
 */
@RestController
@RequestMapping("/api/v1/traces")
@RequireRole({JwtClaims.ROLE_ADMIN})
public class ApiTraceController {

    private final ApiTraceService service;

    public ApiTraceController(ApiTraceService service) {
        this.service = service;
    }

    /**
     * 分页查询链路 span。
     *
     * @param traceId 链路 ID 筛选（可选）
     * @param entry   入口筛选（可选）
     * @param status  结果筛选 OK/ERROR（可选）
     * @param from    起始时间 epoch ms（可选）
     * @param to      结束时间 epoch ms（可选）
     * @param page    页码（从 1 开始）
     * @param size    每页条数（上限 200）
     */
    @GetMapping
    public Result<PageResult<ApiTraceSpanView>> page(@RequestParam(required = false) String traceId,
                                                     @RequestParam(required = false) String entry,
                                                     @RequestParam(required = false) String status,
                                                     @RequestParam(required = false) Long from,
                                                     @RequestParam(required = false) Long to,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        String tenantId = TenantContext.requireTenantId();
        ApiTraceQuery query = new ApiTraceQuery(tenantId, traceId, entry, status,
                from == null ? 0L : Math.max(from, 0L), to == null ? 0L : Math.max(to, 0L));
        PageResult<ApiTraceSpan> pageResult = service.page(query, page, size);
        List<ApiTraceSpanView> items = pageResult.items().stream().map(ApiTraceSpanView::from).toList();
        return Result.ok(PageResult.of(pageResult.total(), pageResult.page(), pageResult.size(), items));
    }

    /**
     * 某条链路的完整 span 时间线。
     *
     * @param traceId 链路 ID（路径变量）
     */
    @GetMapping("/{trace_id}")
    public Result<List<ApiTraceSpanView>> detail(@PathVariable("trace_id") String traceId) {
        String tenantId = TenantContext.requireTenantId();
        List<ApiTraceSpanView> items = service.traceDetail(tenantId, traceId).stream()
                .map(ApiTraceSpanView::from)
                .toList();
        return Result.ok(items);
    }
}
