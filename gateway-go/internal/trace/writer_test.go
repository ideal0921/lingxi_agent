package trace

import (
	"database/sql"
	"errors"
	"strings"
	"sync"
	"testing"
	"time"
)

// fakeResult 满足 sql.Result 的最小桩实现。
type fakeResult struct{}

func (fakeResult) LastInsertId() (int64, error) { return 0, nil }
func (fakeResult) RowsAffected() (int64, error) { return 0, nil }

// fakeExecer 捕获批量插入调用（可注入错误）的桩实现。
type fakeExecer struct {
	mu      sync.Mutex
	queries []string
	args    [][]any
	err     error
}

func (f *fakeExecer) Exec(query string, args ...any) (sql.Result, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.queries = append(f.queries, query)
	f.args = append(f.args, args)
	if f.err != nil {
		return nil, f.err
	}
	return fakeResult{}, nil
}

func (f *fakeExecer) calls() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.queries)
}

// waitForCalls 轮询等待写入器完成指定次数落库（避免依赖固定 sleep 造成不稳定）。
func waitForCalls(t *testing.T, exec *fakeExecer, want int) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if exec.calls() >= want {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("等待落库超时：期望 %d 次，实际 %d 次", want, exec.calls())
}

// TestWriterBatchFlush 满批即落库：批量插入语句含全部列且参数按行展开。
func TestWriterBatchFlush(t *testing.T) {
	exec := &fakeExecer{}
	w := newWriter(exec, nil, WriterConfig{BatchSize: 2, FlushInterval: time.Hour, QueueSize: 8})
	w.Start()
	defer w.Close()

	w.Record(Span{SpanID: "s1", TraceID: "t1", Service: ServiceGateway, Component: ComponentHTTP})
	w.Record(Span{SpanID: "s2", TraceID: "t1", Service: ServiceGateway, Component: ComponentHTTP})
	waitForCalls(t, exec, 1)

	if got := exec.calls(); got != 1 {
		t.Fatalf("满批应触发 1 次落库，实际 %d", got)
	}
	if !strings.Contains(exec.queries[0], "INSERT INTO lingxi_api_trace") {
		t.Fatalf("落库语句不符: %s", exec.queries[0])
	}
	if n := len(exec.args[0]); n != 2*columnCount {
		t.Fatalf("两行参数应为 %d 个，实际 %d", 2*columnCount, n)
	}
	if exec.args[0][0] != "s1" || exec.args[0][columnCount] != "s2" {
		t.Fatalf("参数顺序不符: s1=%v s2=%v", exec.args[0][0], exec.args[0][columnCount])
	}
}

// TestWriterTimerFlush 未满批时按 flushInterval 定时落库。
func TestWriterTimerFlush(t *testing.T) {
	exec := &fakeExecer{}
	w := newWriter(exec, nil, WriterConfig{BatchSize: 100, FlushInterval: 20 * time.Millisecond, QueueSize: 8})
	w.Start()
	defer w.Close()

	w.Record(Span{SpanID: "s1", TraceID: "t1"})
	waitForCalls(t, exec, 1)
}

// TestWriterDropsWhenQueueFull 队列满即丢弃并计数。
//
// 说明：刻意不调用 Start（无后台消费者），队列不会被排空，
// 从而可确定性地验证「满则丢弃」这一旁路降级行为。
func TestWriterDropsWhenQueueFull(t *testing.T) {
	exec := &fakeExecer{}
	w := newWriter(exec, nil, WriterConfig{BatchSize: 100, FlushInterval: time.Hour, QueueSize: 2})

	for i := 0; i < 5; i++ {
		w.Record(Span{SpanID: "s", TraceID: "t"})
	}
	if got := w.Dropped(); got != 3 {
		t.Fatalf("队列容量 2 时应有 3 条被丢弃，实际 %d", got)
	}
	if exec.calls() != 0 {
		t.Fatal("无消费者时不应触发落库")
	}
}

// TestWriterFlushErrorDoesNotPanic 底层写库报错只告警，不得 panic、不得中断写入器。
func TestWriterFlushErrorDoesNotPanic(t *testing.T) {
	exec := &fakeExecer{err: errors.New("db is down")}
	w := newWriter(exec, nil, WriterConfig{BatchSize: 1, FlushInterval: 20 * time.Millisecond, QueueSize: 8})
	w.Start()
	defer w.Close()

	w.Record(Span{SpanID: "s1", TraceID: "t1"})
	waitForCalls(t, exec, 1)

	// 写库失败后写入器仍可用：再记录一条应再次尝试落库。
	w.Record(Span{SpanID: "s2", TraceID: "t1"})
	waitForCalls(t, exec, 2)
}

// TestWriterNoopWhenDisabledOrDSNEmpty DSN 为空或总开关关闭时写入器为 no-op，
// 保证无 DB 环境仍能正常启动与运行。
func TestWriterNoopWhenDisabledOrDSNEmpty(t *testing.T) {
	cases := []struct {
		name string
		cfg  WriterConfig
	}{
		{"DSN 为空", WriterConfig{Enabled: true, DSN: ""}},
		{"总开关关闭", WriterConfig{Enabled: false, DSN: "root:pwd@tcp(127.0.0.1:3306)/lingxi"}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			w := NewWriter(tc.cfg)
			if w.Enabled() {
				t.Fatal("应为 no-op 写入器")
			}
			w.Start() // 不应启动任何 goroutine
			w.Record(Span{SpanID: "s1", TraceID: "t1"})
			if got := w.Dropped(); got != 0 {
				t.Fatalf("no-op 写入器不应计数丢弃，实际 %d", got)
			}
			w.Close() // 不应 panic
		})
	}
}

// TestBuildInsertTruncatesErrorMsg error_msg 超长按列宽截断（VARCHAR(512)）。
func TestBuildInsertTruncatesErrorMsg(t *testing.T) {
	long := strings.Repeat("错", errorMsgMaxRunes+10)
	query, args := buildInsert([]Span{{SpanID: "s1", ErrorMsg: long}})
	if !strings.Contains(query, "INSERT INTO lingxi_api_trace") {
		t.Fatalf("语句不符: %s", query)
	}
	got, _ := args[14].(string)
	if len([]rune(got)) != errorMsgMaxRunes {
		t.Fatalf("error_msg 应截断为 %d 字符，实际 %d", errorMsgMaxRunes, len([]rune(got)))
	}
}
