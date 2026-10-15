package com.lingxi.start.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.lingxi.session.ai.SuggestResult;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import lingxi.agentsync.v1.AgentSync.GenerateSuggestRequest;
import lingxi.agentsync.v1.AgentSync.GenerateSuggestResponse;
import lingxi.agentsync.v1.AgentSyncServiceGrpc;

/**
 * AI 域同步客户端单测（M4-4.6）：用进程内 gRPC 传输 + 真实服务端替身，
 * 离线验证正常透传、降级原因透传、出站超时与过载的状态码映射。
 */
class PythonSuggestRepliesClientTest {

    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (server != null) {
            server.shutdownNow();
        }
    }

    @Test
    void normalResponseIsPassedThrough() throws IOException {
        PythonSuggestRepliesClient client = startClient(new AgentSyncServiceGrpc.AgentSyncServiceImplBase() {
            @Override
            public void suggestReplies(GenerateSuggestRequest request,
                                       StreamObserver<GenerateSuggestResponse> observer) {
                observer.onNext(GenerateSuggestResponse.newBuilder()
                        .setOk(true)
                        .addSuggestions("建议A")
                        .addSuggestions("建议B")
                        .setElapsedMs(20)
                        .build());
                observer.onCompleted();
            }
        }, 1000);

        SuggestResult result = client.ask(request());

        assertTrue(result.ok());
        assertFalse(result.degraded());
        assertFalse(result.queued());
        assertEquals(List.of("建议A", "建议B"), result.suggestions(), "候选应原样透传");
    }

    @Test
    void degradedResponseReasonIsPassedThrough() throws IOException {
        PythonSuggestRepliesClient client = startClient(new AgentSyncServiceGrpc.AgentSyncServiceImplBase() {
            @Override
            public void suggestReplies(GenerateSuggestRequest request,
                                       StreamObserver<GenerateSuggestResponse> observer) {
                observer.onNext(GenerateSuggestResponse.newBuilder()
                        .setOk(false).setDegraded(true).setDegradeReason("BUSY").build());
                observer.onCompleted();
            }
        }, 1000);

        SuggestResult result = client.ask(request());

        assertTrue(result.degraded(), "服务端 degraded=true 应映射为降级");
        assertFalse(result.ok());
        assertEquals("BUSY", result.degradeReason(), "服务端降级原因应透传");
    }

    @Test
    void deadlineExceededMapsToTimeout() throws IOException {
        PythonSuggestRepliesClient client = startClient(new AgentSyncServiceGrpc.AgentSyncServiceImplBase() {
            @Override
            public void suggestReplies(GenerateSuggestRequest request,
                                       StreamObserver<GenerateSuggestResponse> observer) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                observer.onNext(GenerateSuggestResponse.newBuilder().setOk(true).build());
                observer.onCompleted();
            }
        }, 150);

        SuggestResult result = client.ask(request());

        assertTrue(result.degraded());
        assertEquals("TIMEOUT", result.degradeReason(), "出站超时应映射为 TIMEOUT");
    }

    @Test
    void resourceExhaustedMapsToBusy() throws IOException {
        PythonSuggestRepliesClient client = startClient(new AgentSyncServiceGrpc.AgentSyncServiceImplBase() {
            @Override
            public void suggestReplies(GenerateSuggestRequest request,
                                       StreamObserver<GenerateSuggestResponse> observer) {
                observer.onError(Status.RESOURCE_EXHAUSTED.withDescription("并发闸门已满").asRuntimeException());
            }
        }, 1000);

        SuggestResult result = client.ask(request());

        assertTrue(result.degraded());
        assertEquals("BUSY", result.degradeReason(), "过载应映射为 BUSY");
    }

    /** 启动进程内服务端（不触网）并返回指向它的客户端。 */
    private PythonSuggestRepliesClient startClient(AgentSyncServiceGrpc.AgentSyncServiceImplBase impl,
                                                   long timeoutMs) throws IOException {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).addService(impl).build().start();
        channel = InProcessChannelBuilder.forName(name).build();
        return new PythonSuggestRepliesClient(channel, timeoutMs);
    }

    private static GenerateSuggestRequest request() {
        return GenerateSuggestRequest.newBuilder()
                .setRequestId("req-1")
                .setTenantId("tenant-1")
                .setSessionId("s-1")
                .setAgentId("agent-1")
                .setIntent("退款")
                .setSummary("摘要")
                .setMaxSuggestions(3)
                .build();
    }
}
