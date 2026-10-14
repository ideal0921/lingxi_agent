package com.lingxi.knowledge.domain.event;

/**
 * 知识库事件发布端口（依赖倒置）：文档上传落库后投递「入库」事件，交给 AI 域
 * 解析切分并写入向量库。知识域只声明需求，Kafka 实现位于 start 模块。
 */
public interface KnowledgeEventPublisher {

    /**
     * 投递文档入库事件（对应主题 {@code kb.doc.ingest}）。
     *
     * @param docId    文档 ID（AI 域按此 ID 全量替换切片）
     * @param tenantId 租户 ID（多租户隔离）
     * @param kbId     知识库 ID
     * @param version  文档版本号
     * @param fileName 原始文件名（AI 域据此保留扩展名以选择解析器）
     * @param fileUrl  预签名下载 URL
     */
    void publishIngest(String docId, String tenantId, String kbId, int version,
                       String fileName, String fileUrl);

    /**
     * 投递文档下线事件（对应主题 {@code kb.doc.delete}），AI 域据此清理向量库切片。
     *
     * <p>原件的物理删除由知识域负责（对象存储归属本域），事件仅承载向量清理所需标识。</p>
     *
     * @param docId    文档 ID
     * @param tenantId 租户 ID（清理时强制按租户过滤，避免误删他人数据）
     * @param kbId     知识库 ID
     */
    void publishDelete(String docId, String tenantId, String kbId);
}
