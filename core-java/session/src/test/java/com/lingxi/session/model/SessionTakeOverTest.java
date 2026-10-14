package com.lingxi.session.model;

import com.lingxi.session.state.SessionStateMachine.State;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客服接管测试：正常接管、重复接管拒绝、参数校验与并发抢单原子性。
 * 并发场景覆盖 M2-B 验收边界「并发抢单只成功一次」。
 */
class SessionTakeOverTest {

    private Session newSession() {
        return new Session("tenant-1", "user-1", "web");
    }

    @Test
    void takeOverFromInitSucceeds() {
        Session session = newSession();
        Session.TakeOverResult result = session.takeOver("agent-1");
        assertTrue(result.ok());
        assertEquals(State.HUMAN_CHAT, session.getState());
        assertEquals("agent-1", session.getAgentId());
    }

    @Test
    void takeOverFromHumanChatFails() {
        Session session = newSession();
        session.takeOver("agent-1");
        Session.TakeOverResult second = session.takeOver("agent-2");
        assertFalse(second.ok(), "已接管会话不可被重复接管");
        assertFalse(second.reason().isBlank());
        assertEquals("agent-1", session.getAgentId());
    }

    @Test
    void takeOverFromClosedFails() {
        Session session = newSession();
        session.changeState(State.CLOSED);
        Session.TakeOverResult result = session.takeOver("agent-1");
        assertFalse(result.ok());
        assertEquals(State.CLOSED, session.getState());
    }

    @Test
    void takeOverBlankAgentFails() {
        Session session = newSession();
        Session.TakeOverResult result = session.takeOver(" ");
        assertFalse(result.ok());
        assertEquals(State.INIT, session.getState());
    }

    @Test
    void concurrentTakeOverOnlyOneWins() throws InterruptedException {
        int workers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workers);
        Session session = newSession();
        AtomicInteger successCount = new AtomicInteger();

        for (int i = 0; i < workers; i++) {
            String agent = "agent-" + i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    if (session.takeOver(agent).ok()) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        start.countDown();
        done.await();
        pool.shutdown();

        assertEquals(1, successCount.get(), "并发抢单仅一个坐席成功");
        assertNotNull(session.getAgentId());
        assertEquals(State.HUMAN_CHAT, session.getState());
    }
}
