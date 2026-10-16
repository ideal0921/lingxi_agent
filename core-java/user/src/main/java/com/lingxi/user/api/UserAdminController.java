package com.lingxi.user.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.lingxi.common.result.PageResult;
import com.lingxi.common.result.Result;
import com.lingxi.common.security.JwtClaims;
import com.lingxi.common.security.RequireRole;
import com.lingxi.user.api.dto.UserDtos.ChangeStatusRequest;
import com.lingxi.user.api.dto.UserDtos.CreateUserRequest;
import com.lingxi.user.api.dto.UserDtos.ResetPasswordRequest;
import com.lingxi.user.api.dto.UserDtos.UserView;
import com.lingxi.user.application.UserAdminService;
import com.lingxi.user.domain.LingxiUser;

import jakarta.validation.Valid;

/**
 * 用户管理接口（M5-5.6，管理后台）：创建账号、分页查询、重置口令、启用/禁用。
 *
 * <p>权限：仅 `admin`（网关侧另有同规则的路由拦截，双层防护）；
 * 租户一律取可信上下文，前端不得指定。</p>
 */
@RestController
@RequestMapping("/api/v1/users")
@RequireRole({JwtClaims.ROLE_ADMIN})
public class UserAdminController {

    private final UserAdminService userAdminService;

    public UserAdminController(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }

    /** 分页查询本租户账号。 */
    @GetMapping
    public Result<PageResult<UserView>> list(@RequestParam(required = false) String keyword,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        PageResult<LingxiUser> result = userAdminService.list(keyword, page, size);
        return Result.ok(PageResult.of(result.total(), result.page(), result.size(),
                result.items().stream().map(UserView::from).toList()));
    }

    /** 创建账号。 */
    @PostMapping
    public Result<UserView> create(@Valid @RequestBody CreateUserRequest request) {
        LingxiUser user = userAdminService.create(request.username(), request.password(),
                request.displayName(), request.role());
        return Result.ok(UserView.from(user));
    }

    /** 重置账号口令。 */
    @PostMapping("/{userId}/password")
    public Result<Void> resetPassword(@PathVariable String userId,
                                      @Valid @RequestBody ResetPasswordRequest request) {
        userAdminService.resetPassword(userId, request.password());
        return Result.ok();
    }

    /** 启用/禁用账号（内置管理员不可禁用）。 */
    @PostMapping("/{userId}/status")
    public Result<UserView> changeStatus(@PathVariable String userId,
                                         @Valid @RequestBody ChangeStatusRequest request) {
        return Result.ok(UserView.from(userAdminService.changeStatus(userId, request.status())));
    }
}
