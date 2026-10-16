package com.lingxi.session.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lingxi.session.model.Session;
import com.lingxi.session.model.SessionMessage;

/**
 * 存储侧分配 seq 的单测（走 InMemory，与既有内存测试风格一致）：
 * 验证 {@code appendMessage} 连续调用返回**递增且连续**的权威 seq，
 * 且消息按草稿字段落库、聚合条数同步增长（既有行为不被破坏）。
 */
class SessionRepositoryAppendMessageTest {

    private InMemorySessionRepository repository;
    private Session session;

    @BeforeEach
    void setUp() {
        repository = new InMemorySessionRepository();
        session = repository.save(new Session("tenant-1", "u1", "web"));
    }

    @Test
    void appendMessageReturnsConsecutiveIncreasingSeq() {
        assertEquals(1, append("user", "u1", "第一条"));
        assertEquals(2, append("agent", "ai", "第二条"));
        assertEquals(3, append("user", "u1", "第三条"));

        Session stored = repository.findById(session.getId()).orElseThrow();
        assertEquals(3, stored.messageCount(), "聚合条数应随存储侧追加同步增长");
        // 断连续传游标恰好覆盖 1..3，无重复、无空洞
        List<SessionMessage> messages = stored.pullAfter(0, 10);
        assertEquals(List.of(1L, 2L, 3L),
                messages.stream().map(SessionMessage::seq).toList(), "seq 应为连续的 1..3");
    }

    @Test
    void appendMessageHonoursDraftFields() {
        SessionMessage draft = new SessionMessage("ai-1", session.getId(), "agent", "ai",
                "流式回答", 0L, Instant.ofEpochMilli(1723680000000L));

        int seq = repository.appendMessage(session.getId(), draft);

        assertEquals(1, seq);
        SessionMessage stored = repository.findById(session.getId()).orElseThrow().pullAfter(0, 1).get(0);
        assertEquals("ai-1", stored.messageId(), "沿用草稿的消息 ID");
        assertEquals("agent", stored.sender());
        assertEquals("ai", stored.senderId());
        assertEquals("流式回答", stored.content());
        // 内存实现复用聚合的 appendMessage（自带 Instant.now()），故只断言时间戳有效，不比对草稿值
        assertTrue(stored.timestampMillis() > 0, "消息时间戳应为有效值");
    }

    @Test
    void appendMessageOnUnknownSessionFails() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> repository.appendMessage("no-such-session", draft("user", "u1", "内容")));
        assertTrue(error.getMessage().contains("会话不存在"), "语义与 MySQL 实现一致：会话不存在即失败");
    }

    private int append(String sender, String senderId, String content) {
        return repository.appendMessage(session.getId(), draft(sender, senderId, content));
    }

    private static SessionMessage draft(String sender, String senderId, String content) {
        return new SessionMessage(UUID.randomUUID().toString(), null, sender, senderId, content, 0L, Instant.now());
    }
}
