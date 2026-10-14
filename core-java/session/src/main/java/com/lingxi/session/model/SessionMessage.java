package com.lingxi.session.model;

import java.time.Instant;

/**
 * 会话内单条消息：seq 为断连续传游标，全局唯一 messageId。
 */
public record SessionMessage(
        String messageId,
        String sessionId,
        String sender,
        String senderId,
        String content,
        long seq,
        Instant timestamp) {

    /** 返回毫秒时间戳，供 proto 传输。 */
    public long timestampMillis() {
        return timestamp.toEpochMilli();
    }
}
