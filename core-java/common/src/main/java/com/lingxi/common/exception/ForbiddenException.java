package com.lingxi.common.exception;

/**
 * 无权限异常（M5-5.6）：已认证但角色不足，HTTP 403。
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
