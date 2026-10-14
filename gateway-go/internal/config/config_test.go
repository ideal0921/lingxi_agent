package config

import (
	"reflect"
	"testing"
)

func TestSplitAndTrim(t *testing.T) {
	cases := []struct {
		name  string
		input string
		want  []string
	}{
		{"单地址", "localhost:9092", []string{"localhost:9092"}},
		{"多地址含空白", " a:9092 , b:9092 ", []string{"a:9092", "b:9092"}},
		{"尾部空项剔除", "a:9092,", []string{"a:9092"}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := splitAndTrim(tc.input); !reflect.DeepEqual(got, tc.want) {
				t.Fatalf("splitAndTrim(%q) = %v, 期望 %v", tc.input, got, tc.want)
			}
		})
	}
}

func TestKafkaDefaultsDisabled(t *testing.T) {
	// 未显式开启时网关不应连接 Kafka（本地无 Kafka 也能启动）
	cfg := Load()
	if cfg.KafkaEnabled {
		t.Fatal("默认应关闭 Kafka 消费")
	}
	if cfg.KafkaChunkTopic != "lingxi.ai.chunk" {
		t.Fatalf("默认片段主题不符: %s", cfg.KafkaChunkTopic)
	}
}
