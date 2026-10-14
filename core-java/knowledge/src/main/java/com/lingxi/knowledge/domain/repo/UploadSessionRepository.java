package com.lingxi.knowledge.domain.repo;

import com.lingxi.knowledge.domain.UploadSession;

import java.util.Optional;

/**
 * 分片上传会话仓储端口（会话随合并/中止结束，采用物理删除清理）。
 */
public interface UploadSessionRepository {

    UploadSession save(UploadSession session);

    Optional<UploadSession> findById(String uploadId);

    void delete(String uploadId);
}
