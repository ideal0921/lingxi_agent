package com.lingxi.log.domain;

/**
 * 操作结果（M5-5.6）：成功 / 失败（业务失败） / 拒绝（无权限或被隔离拦截）。
 */
public enum OperationResult {
    SUCCESS,
    FAILED,
    DENIED
}
