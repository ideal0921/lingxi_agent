package com.lingxi.trace.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.lingxi.trace.domain.ApiTraceSpan;
import com.lingxi.trace.domain.repo.ApiTraceRepository;

/**
 * 异步批量写入器单测（M5-5.9）：满批 flush、定时 flush、队列满丢弃并计数、写库异常不抛出。
 */
class ApiTraceBatchWriterTest {

    private final List<ApiTraceBatchWriter> writers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        writers.forEach(ApiTraceBatchWriter::shutdown);
    }

    @Test
    void flushesImmediatelyWhenBatchFull() {
        RecordingTraceRepository repository = new RecordingTraceRepository();
        // 间隔设很大：能否及时落库只能靠「满批触发」，证明不是等到定时才写
        ApiTraceBatchWriter writer = newWriter(repository, 3, 60_000, 100);

        writer.record(span("s1"));
        writer.record(span("s2"));
        writer.record(span("s3"));

        awaitUntil(() -> repository.spans().size() >= 3);
        assertEquals(3, repository.spans().size(), "满批后应立即落库");
    }

    @Test
    void flushesByTimerWithoutFullBatch() {
        RecordingTraceRepository repository = new RecordingTraceRepository();
        ApiTraceBatchWriter writer = newWriter(repository, 100, 100, 100);

        // 未满批也应在一个 flush 间隔内落库（定时 flush）
        writer.record(span("s1"));

        awaitUntil(() -> repository.spans().size() >= 1);
        assertEquals(1, repository.spans().size());
    }

    @Test
    void dropsAndCountsWhenQueueFull() {
        BlockingTraceRepository repository = new BlockingTraceRepository();
        // 批大小 1、队列容量 2：worker 取出即阻塞在写库，队列可被填满
        ApiTraceBatchWriter writer = newWriter(repository, 1, 50, 2);

        writer.record(span("s1"));
        // 等 worker 真正进入写库（此时它已取走 s1、队列为空）
        awaitUntil(() -> repository.spans().size() >= 1);

        writer.record(span("s2"));
        writer.record(span("s3"));
        writer.record(span("s4")); // 队列已满 → 丢弃

        assertEquals(1, writer.droppedCount(), "队列满应丢弃并计数");
        repository.release();
    }

    @Test
    void writeFailureIsSwallowed() {
        RecordingTraceRepository repository = new RecordingTraceRepository();
        repository.failOnAppend = true;
        ApiTraceBatchWriter writer = newWriter(repository, 1, 50, 10);

        // 写库异常只 WARN：绝不向调用方抛出，且不中断 worker
        assertDoesNotThrow(() -> {
            writer.record(span("s1"));
            writer.flushNow();
        });
        writer.record(span("s2"));
        writer.flushNow();
        assertTrue(repository.spans().isEmpty(), "落库失败不应写入任何数据");
    }

    private ApiTraceBatchWriter newWriter(ApiTraceRepository repository, int batchSize, long intervalMs,
                                          int queueSize) {
        ApiTraceBatchWriter writer = new ApiTraceBatchWriter(repository, batchSize, intervalMs, queueSize);
        writers.add(writer);
        return writer;
    }

    private static ApiTraceSpan span(String spanId) {
        return new ApiTraceSpan(spanId, "trace-1", "", "core-java", "HTTP", "/api/v1/ping", "GET",
                "OK", 200, "tenant-1", "u-1", "user", "127.0.0.1", 5, "", 1000L);
    }

    private static void awaitUntil(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待被中断");
            }
        }
        throw new AssertionError("等待条件超时");
    }

    /** 写库阻塞型仓储：appendBatch 记录后阻塞，用于确定性地制造「队列满」。 */
    private static final class BlockingTraceRepository implements ApiTraceRepository {

        private final CountDownLatch gate = new CountDownLatch(1);
        private final List<ApiTraceSpan> spans = new CopyOnWriteArrayList<>();

        @Override
        public void appendBatch(List<ApiTraceSpan> batch) {
            if (batch != null) {
                spans.addAll(batch);
            }
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        void release() {
            gate.countDown();
        }

        List<ApiTraceSpan> spans() {
            return spans;
        }

        @Override
        public long count(ApiTraceQuery query) {
            return 0;
        }

        @Override
        public List<ApiTraceSpan> list(ApiTraceQuery query, int offset, int limit) {
            return List.of();
        }

        @Override
        public List<ApiTraceSpan> listByTraceId(String traceId) {
            return List.of();
        }
    }
}
