package com.lingxi.common.exception;

/**
 * 业务异常：携带错误码与提示信息，由全局异常处理器统一转换。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
