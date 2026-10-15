package com.lingxi.workflow.domain;

/**
 * 工作流启停状态：停用后不参与 M4-4.8 的版本灰度路由。
 */
public enum WorkflowStatus {

    /** 启用（默认）。 */
    ENABLED,
    /** 停用。 */
    DISABLED;

    /**
     * 解析持久化字符串。
     *
     * @param value 数据库中的状态字符串
     * @return 匹配的枚举；无法识别时返回 {@code null}
     */
    public static WorkflowStatus fromValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (WorkflowStatus status : values()) {
            if (status.name().equalsIgnoreCase(value.trim())) {
                return status;
            }
        }
        return null;
    }
}
