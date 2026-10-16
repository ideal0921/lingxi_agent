package com.lingxi.common.tenant;

/**
 * 租户上下文缺失异常（M5-5.5）：请求未携带可信租户身份时抛出。
 * gRPC 层映射为 {@code UNAUTHENTICATED}，不泄露内部细节。
 */
public class MissingTenantContextException extends RuntimeException {

    public MissingTenantContextException(String message) {
        super(message);
    }
}
