package com.lingxi.common.trace;

/**
 * 链路上下文持有者（M5-5.9）：按线程绑定当前请求的 traceId 与 spanId。
 *
 * <p>写入方为接入层（HTTP 过滤器 / gRPC 服务端拦截器 / Kafka 消费者），业务代码只读，
 * 以便在出站（Kafka 生产、下游调用）时把链路标识继续透传。</p>
 *
 * <p>为什么用 ThreadLocal：与 {@link com.lingxi.common.tenant.TenantContext} 保持一致，
 * HTTP 请求线程与 gRPC 回调线程内有效；线程用毕必须 {@link #clear()}，防止容器/线程池复用导致链路串号。</p>
 */
public final class TraceContext {

    private static final ThreadLocal<TraceScope> HOLDER = new ThreadLocal<>();

    private TraceContext() {
    }

    /** 链路作用域：traceId 与当前 spanId（空串表示未绑定）。 */
    public record TraceScope(String traceId, String spanId) {

        public static final TraceScope EMPTY = new TraceScope("", "");
    }

    /** 绑定当前线程的链路作用域（调用方负责在用完后 {@link #clear()}）。 */
    public static void set(String traceId, String spanId) {
        HOLDER.set(new TraceScope(text(traceId), text(spanId)));
    }

    /** 当前链路作用域；未绑定时返回空作用域。 */
    public static TraceScope current() {
        TraceScope scope = HOLDER.get();
        return scope == null ? TraceScope.EMPTY : scope;
    }

    /** 当前 traceId；未绑定时为空串。 */
    public static String traceId() {
        return current().traceId();
    }

    /** 当前 spanId；未绑定时为空串。 */
    public static String spanId() {
        return current().spanId();
    }

    /** 父 span：下游以此为 parentSpanId 形成父子关系；未绑定时为空串。 */
    public static String parentSpanId() {
        return spanId();
    }

    /** 是否存在链路上下文（无上下文时下游视为新起一条链路）。 */
    public static boolean hasTrace() {
        return !current().traceId().isEmpty();
    }

    /** 清理当前线程绑定，避免线程复用时链路串号。 */
    public static void clear() {
        HOLDER.remove();
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
