package trace

import (
	"database/sql"
	"fmt"
	"log"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	// MySQL 驱动：仅在本包注册 driver，供 database/sql 打开连接。
	_ "github.com/go-sql-driver/mysql"
)

// insertColumns 与 lingxi_api_trace 建表列顺序严格一致（schema.sql）。
const insertColumns = "span_id, trace_id, parent_span_id, service, component, entry, http_method, " +
	"status, status_code, tenant_id, user_id, role, client_ip, elapsed_ms, error_msg, started_at"

// columnCount 每行占位符个数，与 insertColumns 字段数保持一致。
const columnCount = 16

// errorMsgMaxRunes error_msg 列宽（VARCHAR(512)），超长按字符截断。
const errorMsgMaxRunes = 512

// WriterConfig Writer 构造参数（来自 config 的环境变量）。
type WriterConfig struct {
	// DSN MySQL 连接串；为空即关闭写入（保证无 DB 环境仍能启动）。
	DSN string
	// Enabled 链路追踪总开关（LINGXI_TRACE_ENABLED）。
	Enabled bool
	// BatchSize 满批落库阈值。
	BatchSize int
	// FlushInterval 定时落库间隔。
	FlushInterval time.Duration
	// QueueSize 有界队列容量（满则丢弃并计数）。
	QueueSize int
}

// execer 取 database/sql 的最小执行接口，便于测试注入桩实现。
type execer interface {
	Exec(query string, args ...any) (sql.Result, error)
}

// Writer 旁路 span 写入器：有界 channel + 后台 goroutine 满批/定时 flush。
//
// 为什么这样设计：落库不得进入请求路径（性能红线：首字延迟 <800ms），
// 因此 Record 只做非阻塞入队；队列满则丢弃并计数告警，写库异常只日志、绝不 panic。
type Writer struct {
	db            execer
	closer        func() error
	queue         chan Span
	batchSize     int
	flushInterval time.Duration

	dropped   atomic.Int64
	closed    atomic.Bool
	startOnce sync.Once
	stopOnce  sync.Once
	stopCh    chan struct{}
	wg        sync.WaitGroup
}

// NewWriter 按配置构造写入器并打开连接池。
// DSN 为空或未启用时返回 no-op 写入器；DSN 非法同样降级为 no-op（不阻断网关启动）。
func NewWriter(cfg WriterConfig) *Writer {
	if !cfg.Enabled || strings.TrimSpace(cfg.DSN) == "" {
		return newWriter(nil, nil, cfg)
	}
	db, err := sql.Open("mysql", cfg.DSN)
	if err != nil {
		log.Printf("[trace] MySQL DSN 非法，链路追踪写入降级为 no-op: %v", err)
		return newWriter(nil, nil, cfg)
	}
	// 连接池上限收紧：span 写入是旁路，不应占用过多数据库连接。
	db.SetMaxOpenConns(4)
	db.SetMaxIdleConns(2)
	db.SetConnMaxLifetime(5 * time.Minute)
	return newWriter(db, db.Close, cfg)
}

// newWriter 组装写入器并收敛非法参数（配置错误不应导致运行异常）。
func newWriter(exec execer, closer func() error, cfg WriterConfig) *Writer {
	batchSize := cfg.BatchSize
	if batchSize < 1 {
		batchSize = 1
	}
	flushInterval := cfg.FlushInterval
	if flushInterval <= 0 {
		flushInterval = time.Second
	}
	queueSize := cfg.QueueSize
	if queueSize < 1 {
		queueSize = 1
	}
	return &Writer{
		db:            exec,
		closer:        closer,
		queue:         make(chan Span, queueSize),
		batchSize:     batchSize,
		flushInterval: flushInterval,
		stopCh:        make(chan struct{}),
	}
}

// Enabled 是否真正写入数据库（no-op 时为 false，便于调用方判断）。
func (w *Writer) Enabled() bool {
	return w != nil && w.db != nil
}

// Start 启动后台批量写入 goroutine（no-op 写入器直接返回）。
func (w *Writer) Start() {
	if !w.Enabled() {
		return
	}
	w.startOnce.Do(func() {
		w.wg.Add(1)
		go w.run()
	})
}

// Record 旁路记录一条 span：非阻塞入队，队列满则丢弃并计数告警。
//
// 绝不阻塞调用方——这是「落库不得影响请求链路」约束的核心。
func (w *Writer) Record(span Span) {
	if !w.Enabled() || w.closed.Load() {
		return
	}
	select {
	case w.queue <- span:
	default:
		if n := w.dropped.Add(1); n == 1 || n%1000 == 0 {
			log.Printf("[trace] 队列已满，丢弃 span（累计 %d 条），请检查 MySQL 写入吞吐", n)
		}
	}
}

// Dropped 已丢弃的 span 条数（队列满导致），供观测与测试断言。
func (w *Writer) Dropped() int64 {
	if w == nil {
		return 0
	}
	return w.dropped.Load()
}

// Close 停机：停止接收新 span，排空队列残留并落库，最后关闭连接池。
//
// 先关闭入口（closed 标志）再通知 goroutine，避免停机期间继续入队造成残留丢失。
func (w *Writer) Close() {
	if w == nil {
		return
	}
	w.stopOnce.Do(func() {
		w.closed.Store(true)
		close(w.stopCh)
		w.wg.Wait()
		if w.closer != nil {
			_ = w.closer()
		}
	})
}

// run 后台批量写入循环：满批立即落库，空闲按 flushInterval 定时落库。
func (w *Writer) run() {
	defer w.wg.Done()
	ticker := time.NewTicker(w.flushInterval)
	defer ticker.Stop()

	batch := make([]Span, 0, w.batchSize)
	flush := func() {
		if len(batch) == 0 {
			return
		}
		w.flush(batch)
		batch = batch[:0]
	}
	for {
		select {
		case span := <-w.queue:
			batch = append(batch, span)
			if len(batch) >= w.batchSize {
				flush()
			}
		case <-ticker.C:
			flush()
		case <-w.stopCh:
			// 停机信号：排空队列残留后落库，尽量减少优雅退出时的 span 丢失。
			for {
				select {
				case span := <-w.queue:
					batch = append(batch, span)
					if len(batch) >= w.batchSize {
						flush()
					}
				default:
					flush()
					return
				}
			}
		}
	}
}

// flush 批量插入一批 span；写库异常只告警，绝不 panic、绝不影响调用方。
func (w *Writer) flush(batch []Span) {
	query, args := buildInsert(batch)
	if _, err := w.db.Exec(query, args...); err != nil {
		log.Printf("[trace] 批量写库失败，已丢弃 %d 条 span: %v", len(batch), err)
	}
}

// buildInsert 生成多行 INSERT 与其参数（纯函数，便于单测）。
func buildInsert(batch []Span) (string, []any) {
	row := "(" + strings.TrimSuffix(strings.Repeat("?,", columnCount), ",") + ")"
	placeholders := strings.TrimSuffix(strings.Repeat(row+",", len(batch)), ",")
	args := make([]any, 0, len(batch)*columnCount)
	for _, span := range batch {
		args = append(args,
			span.SpanID, span.TraceID, span.ParentSpanID, span.Service, span.Component, span.Entry,
			span.HTTPMethod, span.Status, span.StatusCode, span.TenantID, span.UserID, span.Role,
			span.ClientIP, span.ElapsedMs, truncateErrorMsg(span.ErrorMsg), span.StartedAt)
	}
	return fmt.Sprintf("INSERT INTO lingxi_api_trace (%s) VALUES %s", insertColumns, placeholders), args
}

// truncateErrorMsg 按列宽截断错误摘要（error_msg 只存摘要，不含请求体）。
func truncateErrorMsg(message string) string {
	if message == "" {
		return ""
	}
	runes := []rune(message)
	if len(runes) > errorMsgMaxRunes {
		return string(runes[:errorMsgMaxRunes])
	}
	return message
}
