package com.lingxi.trace.application;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.lingxi.trace.domain.ApiTraceSpan;
import com.lingxi.trace.domain.repo.ApiTraceRepository;

/**
 * 链路 span 异步批量写入器（M5-5.9）：有界 {@link ArrayBlockingQueue} + 满批/定时 flush 后台线程。
 *
 * <p>为什么异步批量：trace 落库是**旁路**，绝不能占用请求线程影响首字延迟；批量插入把写库开销摊薄。</p>
 *
 * <p>过载与失败策略：队列满立即丢弃并累计计数（周期性 WARN 告警，避免高并发刷屏）；写库异常只 WARN、
 * 绝不向调用方抛出。进程崩溃时未 flush 的 span 会丢失，这是有界队列换「不阻塞主链路」的固有代价。</p>
 */
@Component
public class ApiTraceBatchWriter {

    private static final Logger log = LoggerFactory.getLogger(ApiTraceBatchWriter.class);

    private final ApiTraceRepository repository;
    private final int batchSize;
    private final long flushIntervalMs;
    private final BlockingQueue<ApiTraceSpan> queue;

    /** 累计丢弃数（队列满）：record 线程写、worker 线程读，故用原子量。 */
    private final AtomicLong dropped = new AtomicLong();
    /** 上次已告警的丢弃数（仅 worker 线程访问，无需并发保护）。 */
    private long lastReportedDropped = 0;

    /** 运行标记：停机时置 false 并中断 worker。 */
    private volatile boolean running = true;
    private final Thread worker;

    public ApiTraceBatchWriter(ApiTraceRepository repository,
                               @Value("${lingxi.trace.batch-size:200}") int batchSize,
                               @Value("${lingxi.trace.flush-interval-ms:1000}") long flushIntervalMs,
                               @Value("${lingxi.trace.queue-size:10000}") int queueSize) {
        this.repository = repository;
        this.batchSize = Math.max(batchSize, 1);
        this.flushIntervalMs = Math.max(flushIntervalMs, 1);
        // 队列容量不得小于批大小，否则永远攒不满一批
        this.queue = new ArrayBlockingQueue<>(Math.max(queueSize, this.batchSize));
        // daemon 线程随实例启动，不阻塞 JVM 退出；由 shutdown()/@PreDestroy 负责收尾
        this.worker = new Thread(this::run, "lingxi-trace-writer");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /** 投递一条 span；队列满则丢弃并计数（不阻塞、不抛出）。 */
    public void record(ApiTraceSpan span) {
        if (span == null) {
            return;
        }
        if (!queue.offer(span)) {
            dropped.incrementAndGet();
        }
    }

    /** 累计丢弃数（供监控与测试断言）。 */
    public long droppedCount() {
        return dropped.get();
    }

    /** 同步把队列内待写 span 全部落库（供优雅停机与测试使用）。 */
    public void flushNow() {
        List<ApiTraceSpan> pending = new ArrayList<>();
        queue.drainTo(pending);
        flush(pending);
    }

    /** 优雅停机：停止 worker 并把剩余 span flush 出去，尽量少丢。 */
    @PreDestroy
    public void shutdown() {
        running = false;
        worker.interrupt();
        try {
            worker.join(2000);
        } catch (InterruptedException e) {
            // 收尾阶段被中断：恢复中断标记即可（进程已准备退出）
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        List<ApiTraceSpan> batch = new ArrayList<>(batchSize);
        while (running) {
            try {
                // 阻塞等待至多 flush-interval；有数据则立即返回，天然实现「满批立即 flush、空闲定时 flush」
                ApiTraceSpan first = queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    queue.drainTo(batch, batchSize - batch.size());
                }
                if (!batch.isEmpty()) {
                    flush(batch);
                    batch.clear();
                }
                reportDropped();
            } catch (InterruptedException e) {
                // 停机中断：退出循环后统一收尾
                Thread.currentThread().interrupt();
                break;
            }
        }
        flushNow();
    }

    /** 批量落库；任何异常只 WARN，绝不向调用方或 worker 线程抛出。 */
    private void flush(List<ApiTraceSpan> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        try {
            // 传副本：调用方随即 clear/复用同一列表，避免仓储实现持有引用时内容被清空
            repository.appendBatch(new ArrayList<>(batch));
        } catch (RuntimeException e) {
            log.warn("链路 span 批量落库失败（旁路，不影响主链路）size={} err={}", batch.size(), e.getMessage());
        }
    }

    /** 周期性告警累计丢弃量（丢弃计数在增长时才告警一次，避免刷屏）。 */
    private void reportDropped() {
        long total = dropped.get();
        if (total > lastReportedDropped) {
            log.warn("链路 span 队列已满，累计丢弃 {} 条（trace 落库为旁路，不影响请求）", total);
            lastReportedDropped = total;
        }
    }
}
