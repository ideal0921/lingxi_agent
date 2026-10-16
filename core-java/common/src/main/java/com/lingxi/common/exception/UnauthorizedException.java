package com.lingxi.common.exception;

/**
 * 未认证异常（M5-5.6）：缺少凭证或凭证无效/过期，HTTP 401。
 * 对外提示统一，不区分「用户不存在 / 口令错误 / 令牌过期」，避免信息泄露。
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
