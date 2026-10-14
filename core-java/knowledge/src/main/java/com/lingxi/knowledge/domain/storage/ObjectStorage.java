package com.lingxi.knowledge.domain.storage;

import java.io.InputStream;

/**
 * 对象存储端口（依赖倒置）：知识库原始文件的读写抽象。
 *
 * <p>实现位于 infrastructure（MinIO）；领域与应用层只依赖本接口，便于替换
 * 云 OSS 或在单测中注入内存实现。</p>
 */
public interface ObjectStorage {

    /**
     * 上传对象（流式写入，调用方需保证 {@code sizeBytes} 准确）。
     *
     * @param objectKey     对象路径（tenant/kb/doc/version 维度组织）
     * @param stream        内容流（由调用方负责关闭）
     * @param sizeBytes     内容长度（字节）
     * @param contentType   MIME 类型
     */
    void put(String objectKey, InputStream stream, long sizeBytes, String contentType);

    /**
     * 生成预签名下载 URL，供 AI 域拉取原件（免去其引入对象存储 SDK）。
     *
     * @param objectKey     对象路径
     * @param expirySeconds 有效期（秒）
     */
    String presignedGetUrl(String objectKey, int expirySeconds);

    /**
     * 读取对象内容（用于分片合并等需按序读取的场景，调用方负责关闭流）。
     */
    InputStream get(String objectKey);

    /** 删除对象（下线文档或版本清理）。 */
    void remove(String objectKey);
}
