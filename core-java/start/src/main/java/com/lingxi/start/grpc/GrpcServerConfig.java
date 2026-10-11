package com.lingxi.start.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * gRPC 服务端配置（M1：为网关提供健康检查契约服务，供跨语言链路验收）。
 * 端口通过环境变量 LINGXI_GRPC_PORT 配置，默认 9090。
 */
@Configuration
public class GrpcServerConfig {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerConfig.class);

    @Bean(destroyMethod = "shutdownNow")
    public Server grpcServer(HealthServiceGrpcImpl healthService,
                             @Value("${lingxi.grpc.port:9090}") int port) {
        Server server = ServerBuilder.forPort(port)
                .addService(healthService)
                .build();
        try {
            server.start();
            log.info("gRPC 服务已启动，端口 {}", port);
        } catch (Exception e) {
            throw new IllegalStateException("gRPC 服务启动失败", e);
        }
        return server;
    }

    /** 应用启动后记录 gRPC 就绪状态，便于链路验收确认。 */
    @Bean
    public ApplicationRunner grpcReadyLog(Server grpcServer) {
        return args -> log.info("gRPC 健康检查契约服务就绪：{}", grpcServer.getPort());
    }
}
