package com.lingxi.user.domain;

/**
 * 账号状态（M5-5.6）：禁用后拒绝登录与鉴权，但保留账号数据以便追溯。
 */
public enum UserStatus {

    ENABLED,
    DISABLED;

    /** 解析状态名；非法值返回 null（调用方据此拒绝）。 */
    public static UserStatus fromName(String name) {
        if (name == null) {
            return null;
        }
        try {
            return valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
