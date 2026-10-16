package com.lingxi.start.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.BindableService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.lingxi.common.tenant.TenantGuardInterceptor;

import java.util.List;

/**
 * gRPC 服务端配置（M1：为网关提供健康检查契约服务，供跨语言链路验收）。
 * 端口通过环境变量 LINGXI_GRPC_PORT 配置，默认 9090。
 *
 * <p>M5-5.5：统一挂载 {@link TenantGuardInterceptor}，所有业务服务强制携带可信租户
 * （由 Go 网关经 metadata 下传），并按租户独占并发配额。</p>
 */
@Configuration
public class GrpcServerConfig {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerConfig.class);

    @Bean(destroyMethod = "shutdownNow")
    public Server grpcServer(List<BindableService> services,
                             TenantGuardInterceptor tenantGuardInterceptor,
                             @Value("${lingxi.grpc.port:9090}") int port) {
        ServerBuilder<?> builder = ServerBuilder.forPort(port)
                .intercept(tenantGuardInterceptor);
        for (BindableService service : services) {
            builder.addService(service);
        }
        Server server = builder.build();
        try {
            server.start();
            log.info("gRPC 服务已启动，端口 {}（租户守卫已挂载）", port);
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
