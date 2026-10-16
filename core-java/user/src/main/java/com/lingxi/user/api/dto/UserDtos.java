package com.lingxi.user.api.dto;

import com.lingxi.user.domain.LingxiUser;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 用户/权限域 REST 出入参（M5-5.6）。
 */
public final class UserDtos {

    private UserDtos() {
    }

    /** 登录请求：租户可空（空则回落默认租户，便于单租户部署）。 */
    public record LoginRequest(@NotBlank(message = "不能为空") String username,
                               @NotBlank(message = "不能为空") String password,
                               String tenantId) {
    }

    /** 账号视图（不含任何口令字段）。 */
    public record UserView(String userId, String tenantId, String username, String displayName,
                           String role, String status, long createdAtMs, long lastLoginAtMs) {

        public static UserView from(LingxiUser user) {
            return new UserView(user.getUserId(), user.getTenantId(), user.getUsername(),
                    user.getDisplayName(), user.getRole().code(), user.getStatus().name(),
                    user.getCreatedAtMs(), user.getLastLoginAtMs());
        }
    }

    /** 登录响应：令牌 + 有效期 + 账号信息。 */
    public record LoginView(String token, String tokenType, long expiresInSeconds, UserView user) {
    }

    /** 创建账号请求：租户由服务端按可信上下文取，前端不得指定。 */
    public record CreateUserRequest(@NotBlank(message = "不能为空") String username,
                                    @NotBlank(message = "不能为空") @Size(min = 8, max = 64, message = "长度需 8~64") String password,
                                    String displayName,
                                    @NotBlank(message = "不能为空") String role) {
    }

    /** 重置口令请求。 */
    public record ResetPasswordRequest(@NotBlank(message = "不能为空")
                                       @Size(min = 8, max = 64, message = "长度需 8~64") String password) {
    }

    /** 变更状态请求：ENABLED / DISABLED。 */
    public record ChangeStatusRequest(@NotBlank(message = "不能为空") String status) {
    }
}
