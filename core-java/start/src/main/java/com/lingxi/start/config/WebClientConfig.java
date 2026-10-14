package com.lingxi.start.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * WebClient 出站 HTTP 客户端基础设施（M2-B 就位，M3 供 AI 快捷回复/第三方集成调用）。
 *
 * <p>Servlet 栈（spring-boot-starter-web）下仅启用 WebClient 客户端能力，不切换响应式容器；
 * 依托 Reactor Netty 实现非阻塞 IO 与细粒度超时控制。</p>
 *
 * <p>超时参数环境变量化：{@code LINGXI_HTTP_CONNECT_TIMEOUT_MS}（默认 2000ms）、
 * {@code LINGXI_HTTP_RESPONSE_TIMEOUT_MS}（默认 5000ms，满足工具调用超时 &lt; 5s 约束）。</p>
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient lingxiWebClient(
            @Value("${lingxi.http.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${lingxi.http.response-timeout-ms:5000}") int responseTimeoutMs) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .responseTimeout(Duration.ofMillis(responseTimeoutMs))
                .doOnConnected(conn -> conn.addHandlerLast(
                        new ReadTimeoutHandler(responseTimeoutMs, TimeUnit.MILLISECONDS)));
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();
    }
}
