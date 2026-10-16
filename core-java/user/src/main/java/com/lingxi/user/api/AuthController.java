package com.lingxi.user.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.lingxi.common.result.Result;
import com.lingxi.user.api.dto.UserDtos.LoginRequest;
import com.lingxi.user.api.dto.UserDtos.LoginView;
import com.lingxi.user.api.dto.UserDtos.UserView;
import com.lingxi.user.application.AuthService;

import jakarta.validation.Valid;

/**
 * 认证接口（M5-5.6）：密码登录、当前身份、登出。
 *
 * <p>`/api/v1/auth/login` 是唯一豁免认证的业务接口（否则无法登录）；
 * `me` 与 `logout` 需携带令牌。</p>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 口令登录：失败统一返回 401（不区分用户不存在/口令错误/账号禁用）。 */
    @PostMapping("/login")
    public Result<LoginView> login(@Valid @RequestBody LoginRequest request) {
        AuthService.LoginResult result = authService.login(request.tenantId(), request.username(), request.password());
        return Result.ok(new LoginView(result.token(), "Bearer", result.expiresInSeconds(),
                UserView.from(result.user())));
    }

    /** 当前登录账号（前端登录态校验与用户模块展示）。 */
    @GetMapping("/me")
    public Result<UserView> me() {
        return Result.ok(UserView.from(authService.currentUser()));
    }

    /** 登出：记录审计；令牌为无状态，失效由前端清除本地令牌完成。 */
    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.ok();
    }
}
