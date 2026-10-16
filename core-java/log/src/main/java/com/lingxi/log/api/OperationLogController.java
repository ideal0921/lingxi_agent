package com.lingxi.log.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.lingxi.common.result.PageResult;
import com.lingxi.common.result.Result;
import com.lingxi.common.security.JwtClaims;
import com.lingxi.common.security.RequireRole;
import com.lingxi.common.tenant.TenantContext;
import com.lingxi.log.api.dto.OperationLogView;
import com.lingxi.log.application.OperationLogQuery;
import com.lingxi.log.application.OperationLogService;
import com.lingxi.log.domain.OperationLog;

/**
 * 操作日志查询接口（M5-5.6）：管理后台「系统日志」页数据源。
 *
 * <p>权限：仅 `admin`；租户范围取可信租户上下文（不可由前端指定其他租户）。</p>
 */
@RestController
@RequestMapping("/api/v1/logs")
@RequireRole({JwtClaims.ROLE_ADMIN})
public class OperationLogController {

    private final OperationLogService service;

    public OperationLogController(OperationLogService service) {
        this.service = service;
    }

    /**
     * 分页查询操作日志。
     *
     * @param module 模块筛选（可选）
     * @param action 动作筛选（可选）
     * @param result 结果筛选 SUCCESS/FAILED/DENIED（可选）
     * @param from   起始时间 epoch ms（可选）
     * @param to     结束时间 epoch ms（可选）
     * @param page   页码（从 1 开始）
     * @param size   每页条数（上限 200）
     */
    @GetMapping
    public Result<PageResult<OperationLogView>> page(@RequestParam(required = false) String module,
                                                     @RequestParam(required = false) String action,
                                                     @RequestParam(required = false) String result,
                                                     @RequestParam(required = false) Long from,
                                                     @RequestParam(required = false) Long to,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        String tenantId = TenantContext.requireTenantId();
        OperationLogQuery query = new OperationLogQuery(tenantId, module, action, result,
                from == null ? 0L : Math.max(from, 0L), to == null ? 0L : Math.max(to, 0L));
        PageResult<OperationLog> pageResult = service.page(query, page, size);
        List<OperationLogView> items = pageResult.items().stream().map(OperationLogView::from).toList();
        return Result.ok(PageResult.of(pageResult.total(), pageResult.page(), pageResult.size(), items));
    }
}
