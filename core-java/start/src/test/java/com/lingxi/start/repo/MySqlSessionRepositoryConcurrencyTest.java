package com.lingxi.start.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import javax.sql.DataSource;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import com.lingxi.session.model.Session;
import com.lingxi.session.model.SessionMessage;
import com.lingxi.session.repo.MySqlSessionRepository;
import com.lingxi.session.repo.SessionRepository;
import com.lingxi.session.state.SessionStateMachine.State;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * 真实 MySQL 并发回归（关键：复现并守住「并发覆盖丢消息」）：
 * N 个线程对**同一 sessionId** 并发调用 {@link SessionRepository#appendMessage}，
 * 断言 seq 恰好为 1..N（无重复、无空洞）、消息行数与 {@code MAX(seq)} 均为 N、
 * 会话冗余列 {@code message_count} 为 N。
 *
 * <p>本类属集成测试，默认**跳过**（不污染 {@code mvn test}）。手动运行：
 * <pre>mvn -q -pl start -am test -Dtest=MySqlSessionRepositoryConcurrencyTest \
 *     -Dlingxi.it.mysql=true -Dsurefire.failIfNoSpecifiedTests=false</pre>
 * 依赖本机 MySQL（127.0.0.1:3306/lingxi，root/ideal0921）。</p>
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "lingxi.it.mysql", matches = "true")
class MySqlSessionRepositoryConcurrencyTest {

    private static final String DB_URL = "jdbc:mysql://127.0.0.1:3306/lingxi"
            + "?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8";
    private static final String DB_USER = "root";
    private static final String DB_PASSWORD = "ideal0921";

    /** 并发写线程数（需 < Hikari 连接池上限，保证每线程都能拿到连接后同时竞争行锁）。 */
    private static final int WORKERS = 12;

    @Test
    void concurrentAppendAssignsContiguousSeqUnderRowLock() throws Exception {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(TestConfig.class)) {
            SessionRepository repository = context.getBean(SessionRepository.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            ensureTables(jdbc);

            String sessionId = "it-concurrency-" + UUID.randomUUID();
            long now = System.currentTimeMillis();
            repository.save(Session.restore(sessionId, "tenant-it", "u-it", "web",
                    State.INIT, "", "", now, now, 0, List.of()));

            ConcurrentLinkedQueue<Integer> assignedSeq = new ConcurrentLinkedQueue<>();
            ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
            CountDownLatch ready = new CountDownLatch(WORKERS);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(WORKERS);
            ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
            try {
                for (int i = 0; i < WORKERS; i++) {
                    final int index = i;
                    pool.submit(() -> {
                        ready.countDown();
                        try {
                            start.await();
                            assignedSeq.add(repository.appendMessage(sessionId, new SessionMessage(
                                    "m-" + index, sessionId, "user", "u-it", "内容-" + index, 0L, Instant.now())));
                        } catch (Throwable t) {
                            failures.add(t);
                        } finally {
                            done.countDown();
                        }
                    });
                }
                assertTrue(ready.await(10, TimeUnit.SECONDS), "并发线程未就绪");
                start.countDown();
                assertTrue(done.await(60, TimeUnit.SECONDS), "并发写入超时未完成");
            } finally {
                pool.shutdownNow();
            }

            assertTrue(failures.isEmpty(), "并发写入不应抛异常: " + failures);
            // 消息行数 / MAX(seq) / message_count 三者都应为 N
            Integer rows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM lingxi_session_message WHERE session_id = ?", Integer.class, sessionId);
            Long maxSeq = jdbc.queryForObject(
                    "SELECT MAX(seq) FROM lingxi_session_message WHERE session_id = ?", Long.class, sessionId);
            Integer messageCount = jdbc.queryForObject(
                    "SELECT message_count FROM lingxi_session WHERE session_id = ?", Integer.class, sessionId);
            assertEquals(WORKERS, rows, "消息行数应等于并发数（无覆盖丢失）");
            assertEquals(WORKERS, maxSeq, "MAX(seq) 应等于并发数（无空洞）");
            assertEquals(WORKERS, messageCount, "message_count 应等于并发数");
            // seq 集合恰好为 1..N（无重复）
            List<Integer> sorted = assignedSeq.stream().sorted().toList();
            assertEquals(IntStream.rangeClosed(1, WORKERS).boxed().toList(), sorted,
                    "分配到的 seq 应恰好是 1.." + WORKERS);

            // 清理本次测试数据，避免污染本地库
            jdbc.update("DELETE FROM lingxi_session_message WHERE session_id = ?", sessionId);
            jdbc.update("DELETE FROM lingxi_session WHERE session_id = ?", sessionId);
        }
    }

    /** 幂等建表：仅取会话域两张表（与 start/src/main/resources/schema.sql 保持一致）。 */
    private void ensureTables(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS lingxi_session (
                    session_id        VARCHAR(64)   NOT NULL,
                    tenant_id         VARCHAR(64)   NOT NULL,
                    user_id           VARCHAR(64)   NOT NULL,
                    channel           VARCHAR(32)   NOT NULL DEFAULT '',
                    state             VARCHAR(16)   NOT NULL,
                    agent_id          VARCHAR(64)   NOT NULL DEFAULT '',
                    summary           VARCHAR(2000) NOT NULL DEFAULT '',
                    created_at_ms     BIGINT        NOT NULL,
                    last_active_at_ms BIGINT        NOT NULL,
                    message_count     INT           NOT NULL DEFAULT 0,
                    PRIMARY KEY (session_id),
                    KEY idx_tenant_user_active (tenant_id, user_id, last_active_at_ms DESC)
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS lingxi_session_message (
                    session_id    VARCHAR(64)   NOT NULL,
                    seq           BIGINT        NOT NULL,
                    message_id    VARCHAR(64)   NOT NULL,
                    sender        VARCHAR(16)   NOT NULL,
                    sender_id     VARCHAR(64)   NOT NULL DEFAULT '',
                    content       VARCHAR(4000) NOT NULL DEFAULT '',
                    created_at_ms BIGINT        NOT NULL,
                    PRIMARY KEY (session_id, seq),
                    KEY idx_message_id (message_id)
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
                """);
    }

    /**
     * 最小装配：DataSource + 事务管理器 + JdbcTemplate + {@link MySqlSessionRepository}。
     * 显式开启 {@link EnableTransactionManagement}，使 {@code @Transactional} 与
     * {@code SELECT ... FOR UPDATE} 真正生效（与生产 Spring 上下文一致）。
     */
    @Configuration
    @EnableTransactionManagement
    static class TestConfig {

        @Bean(destroyMethod = "close")
        DataSource dataSource() {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(DB_URL);
            config.setUsername(DB_USER);
            config.setPassword(DB_PASSWORD);
            config.setMaximumPoolSize(16);
            return new HikariDataSource(config);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        SessionRepository sessionRepository(JdbcTemplate jdbcTemplate) {
            return new MySqlSessionRepository(jdbcTemplate);
        }
    }
}
