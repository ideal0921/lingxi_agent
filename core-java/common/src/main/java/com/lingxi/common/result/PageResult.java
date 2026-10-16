package com.lingxi.common.result;

import java.util.List;

/**
 * 统一分页响应体（M5-5.6）：管理后台列表（用户/操作日志）共用。
 *
 * @param total 命中总数（用于前端分页器）
 * @param page  当前页（从 1 开始）
 * @param size  每页条数
 * @param items 当前页数据
 */
public record PageResult<T>(long total, int page, int size, List<T> items) {

    /** 由仓储结果构造分页响应。 */
    public static <T> PageResult<T> of(long total, int page, int size, List<T> items) {
        return new PageResult<>(total, page, size, items == null ? List.of() : items);
    }
}
