package com.lingxi.start.grpc;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

/**
 * AI 域同步通道出站连接（M4-4.6 坐席快捷回复）。
 *
 * <p>Java 通过该 channel 同步调用 Python 的 {@code AgentSyncService}；出站超时由调用点
 * （{@code lingxi.suggest.timeout-ms}，默认 4000ms &lt; Go 端 5s 预算）控制。
 * 通道关闭时（{@code lingxi.suggest.enabled=false}）不建连，避免无谓资源占用。</p>
 */
@Configuration
@ConditionalOnProperty(name = "lingxi.suggest.enabled", havingValue = "true", matchIfMissing = true)
public class PythonSyncChannelConfig {

    private static final Logger log = LoggerFactory.getLogger(PythonSyncChannelConfig.class);

    @Bean(destroyMethod = "shutdownNow")
    public ManagedChannel suggestChannel(@Value("${lingxi.suggest.python-target:127.0.0.1:9095}") String target) {
        log.info("AI 域同步通道已建立 target={}", target);
        return ManagedChannelBuilder.forTarget(target)
                .usePlaintext()
                // 长连接保活：本地/同机房部署，探测失败仅记录不影响业务调用
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .build();
    }
}
