package com.lingxi.start.grpc;

import io.grpc.stub.StreamObserver;
import lingxi.health.v1.Health;
import lingxi.health.v1.HealthServiceGrpc;
import org.springframework.stereotype.Component;

/**
 * gRPC 健康检查服务实现（M1 验收：网关 → Java ping 通）。
 * 跨语言契约由 gateway-api 模块生成。
 */
@Component
public class HealthServiceGrpcImpl extends HealthServiceGrpc.HealthServiceImplBase {

    /** 固定服务名，标识 Java 业务域。 */
    private static final String SERVICE_NAME = "core-java";

    @Override
    public void ping(Health.PingRequest request, StreamObserver<Health.PingResponse> responseObserver) {
        Health.PingResponse response = Health.PingResponse.newBuilder()
                .setStatus("ok")
                .setService(SERVICE_NAME)
                .setTimestampMs(System.currentTimeMillis())
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
