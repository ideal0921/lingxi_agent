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
import com.lingxi.trace.application.ApiTraceService;
import com.lingxi.trace.infrastructure.grpc.ApiTraceGrpcInterceptor;

import java.util.List;

/**
 * gRPC 服务端配置（M1：为网关提供健康检查契约服务，供跨语言链路验收）。
 * 端口通过环境变量 LINGXI_GRPC_PORT 配置，默认 9090。
 *
 * <p>M5-5.5：统一挂载 {@link TenantGuardInterceptor}，所有业务服务强制携带可信租户
 * （由 Go 网关经 metadata 下传），并按租户独占并发配额。</p>
 *
 * <p>M5-5.9：链路拦截器 {@link ApiTraceGrpcInterceptor} **先于租户守卫注册即位于最外层**，
 * 以覆盖被守卫拒绝的调用并直接读 metadata 身份记 span。</p>
 */
@Configuration
public class GrpcServerConfig {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerConfig.class);

    /** 链路拦截器：最外层，读取 metadata 中的 trace/租户身份并记录 span。 */
    @Bean
    public ApiTraceGrpcInterceptor apiTraceGrpcInterceptor(ApiTraceService apiTraceService) {
        return new ApiTraceGrpcInterceptor(apiTraceService);
    }

    @Bean(destroyMethod = "shutdownNow")
    public Server grpcServer(List<BindableService> services,
                             ApiTraceGrpcInterceptor apiTraceGrpcInterceptor,
                             TenantGuardInterceptor tenantGuardInterceptor,
                             @Value("${lingxi.grpc.port:9090}") int port) {
        // 注册顺序即层级：先注册者最外层。Trace 在最外层以覆盖租户守卫（内层）拒绝的调用。
        ServerBuilder<?> builder = ServerBuilder.forPort(port)
                .intercept(apiTraceGrpcInterceptor)
                .intercept(tenantGuardInterceptor);
        for (BindableService service : services) {
            builder.addService(service);
        }
        Server server = builder.build();
        try {
            server.start();
            log.info("gRPC 服务已启动，端口 {}（链路 + 租户守卫已挂载）", port);
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
