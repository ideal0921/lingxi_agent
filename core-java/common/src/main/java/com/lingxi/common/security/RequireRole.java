package com.lingxi.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 角色要求注解（M5-5.6）：标注在 Controller 类或方法上，由 {@link AuthInterceptor} 校验。
 *
 * <p>语义：命中任一列出角色即放行；`admin` 为超级角色，恒放行；
 * 未标注则仅要求「已认证」。</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    /** 允许访问的角色列表（取值见 {@code admin/agent/user}）。 */
    String[] value();
}
