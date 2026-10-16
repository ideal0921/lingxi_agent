package com.lingxi.common.security;

/**
 * 请求元信息上下文（M5-5.6）：由认证拦截器写入来源 IP 与 User-Agent，
 * 供操作日志（审计）在应用层取用；线程用毕即清理。
 */
public final class RequestContext {

    private static final ThreadLocal<RequestMeta> HOLDER = new ThreadLocal<>();

    private RequestContext() {
    }

    /** 来源元信息。 */
    public record RequestMeta(String clientIp, String userAgent) {

        public static final RequestMeta EMPTY = new RequestMeta("", "");
    }

    public static void set(String clientIp, String userAgent) {
        HOLDER.set(new RequestMeta(
                clientIp == null ? "" : clientIp,
                userAgent == null ? "" : userAgent));
    }

    public static RequestMeta current() {
        RequestMeta meta = HOLDER.get();
        return meta == null ? RequestMeta.EMPTY : meta;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
