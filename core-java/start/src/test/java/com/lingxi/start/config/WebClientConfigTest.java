package com.lingxi.start.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * WebClient 基础设施测试：
 * 覆盖 Bean 装配、正常 GET 与响应超时兜底（超时需 &lt; 5s）。
 * 仅加载 {@link WebClientConfig}，不触发全应用上下文（避免与运行中的 gRPC 端口冲突）。
 */
@SpringBootTest(classes = WebClientConfig.class,
        properties = {"lingxi.http.connect-timeout-ms=1000", "lingxi.http.response-timeout-ms=1000"})
class WebClientConfigTest {

    @Autowired
    private WebClient webClient;

    @Test
    void beanPresent() {
        assertNotNull(webClient, "lingxiWebClient Bean 应已装配");
    }

    @Test
    void getSuccess() throws IOException {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/ping", exchange -> {
            byte[] body = "pong".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.setExecutor(executor);
        server.start();
        try {
            String port = String.valueOf(server.getAddress().getPort());
            String result = webClient.get()
                    .uri("http://127.0.0.1:" + port + "/ping")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            assertEquals("pong", result);
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void responseTimeoutFallsBack() throws IOException {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "late".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.setExecutor(executor);
        server.start();
        try {
            String port = String.valueOf(server.getAddress().getPort());
            assertThrows(Exception.class, () -> webClient.get()
                            .uri("http://127.0.0.1:" + port + "/slow")
                            .retrieve()
                            .bodyToMono(String.class)
                            .block(),
                    "响应超时（1s）应抛出异常而非无限阻塞");
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
