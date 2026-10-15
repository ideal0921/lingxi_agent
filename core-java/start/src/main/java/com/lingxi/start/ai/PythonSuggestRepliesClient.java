package com.lingxi.start.ai;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.lingxi.session.ai.SuggestRepliesPort;
import com.lingxi.session.ai.SuggestResult;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lingxi.agentsync.v1.AgentSync.GenerateSuggestRequest;
import lingxi.agentsync.v1.AgentSync.GenerateSuggestResponse;
import lingxi.agentsync.v1.AgentSyncServiceGrpc;

/**
 * AI 域同步通道出站实现（M4-4.6）：以 gRPC 同步调用 Python 生成回复建议草稿。
 *
 * <p>超时预算：Go 端 5s &gt; Java 出站 {@code lingxi.suggest.timeout-ms}（默认 4000ms）
 * &gt; Python 生成 3.5s。出站失败（超时/过载/不可用）不抛异常，转为降级结果交由协调器落异步队列。</p>
 */
@Component
@ConditionalOnProperty(name = "lingxi.suggest.enabled", havingValue = "true", matchIfMissing = true)
public class PythonSuggestRepliesClient implements SuggestRepliesPort {

    private static final Logger log = LoggerFactory.getLogger(PythonSuggestRepliesClient.class);

    private final ManagedChannel channel;
    private final long timeoutMs;

    public PythonSuggestRepliesClient(ManagedChannel suggestChannel,
                                      @Value("${lingxi.suggest.timeout-ms:4000}") long timeoutMs) {
        this.channel = suggestChannel;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public SuggestResult ask(GenerateSuggestRequest request) {
        long startNanos = System.nanoTime();
        try {
            GenerateSuggestResponse response = AgentSyncServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                    .suggestReplies(request);
            long elapsed = elapsedMs(startNanos);
            // 服务端以 gRPC OK + degraded=true 表达「过载/超时」，按其原因透传便于回落异步链路
            if (response.getDegraded()) {
                String reason = response.getDegradeReason();
                log.warn("AI 域同步生成降级 sessionId={} reason={} elapsedMs={}",
                        request.getSessionId(), reason, elapsed);
                return SuggestResult.degraded(reason, elapsed);
            }
            if (!response.getOk()) {
                // 非降级却未产出候选属异常返回，按不可用处理（不谎报成功）
                log.warn("AI 域同步生成未产出候选 sessionId={} elapsedMs={}", request.getSessionId(), elapsed);
                return SuggestResult.degraded("UNAVAILABLE", elapsed);
            }
            List<String> suggestions = response.getSuggestionsList();
            log.debug("AI 域同步生成成功 sessionId={} 候选数={} elapsedMs={}",
                    request.getSessionId(), suggestions.size(), elapsed);
            return SuggestResult.ok(suggestions, elapsed);
        } catch (StatusRuntimeException e) {
            long elapsed = elapsedMs(startNanos);
            String reason = mapReason(e.getStatus().getCode());
            log.warn("AI 域同步调用失败 sessionId={} code={} reason={} elapsedMs={}",
                    request.getSessionId(), e.getStatus().getCode(), reason, elapsed, e);
            return SuggestResult.degraded(reason, elapsed);
        }
    }

    /** gRPC 状态码 → 降级原因：超时→TIMEOUT、过载→BUSY、其余→UNAVAILABLE。 */
    private static String mapReason(Status.Code code) {
        return switch (code) {
            case DEADLINE_EXCEEDED -> "TIMEOUT";
            case RESOURCE_EXHAUSTED -> "BUSY";
            default -> "UNAVAILABLE";
        };
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
