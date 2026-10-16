package com.lingxi.log.application;

/**
 * 操作日志查询条件（M5-5.6）：租户必填（隔离），其余可选。
 *
 * @param tenantId 租户标识（必填，来自可信租户上下文）
 * @param module   模块筛选
 * @param action   动作筛选
 * @param result   结果筛选
 * @param fromMs   起始时间（epoch ms，0 表示不限）
 * @param toMs     结束时间（epoch ms，0 表示不限）
 */
public record OperationLogQuery(String tenantId, String module, String action, String result,
                                long fromMs, long toMs) {

    /** 空字符串归一化为「不限」。 */
    public OperationLogQuery {
        module = blankToNull(module);
        action = blankToNull(action);
        result = blankToNull(result);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
