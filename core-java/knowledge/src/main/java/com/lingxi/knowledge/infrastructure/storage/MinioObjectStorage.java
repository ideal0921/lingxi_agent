package com.lingxi.knowledge.infrastructure.storage;

import com.lingxi.knowledge.domain.storage.ObjectStorage;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * MinIO 对象存储实现：知识库原始文件落对象存储，按 {@code tenant/kb/doc/version} 组织路径。
 *
 * <p>存储桶初始化是**惰性**的：启动时尝试创建失败只告警（不阻断应用启动），首次实际
 * 读写时再重试，避免对象存储故障导致整个服务无法启动。</p>
 */
@Component
public class MinioObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(MinioObjectStorage.class);

    private final MinioClient client;
    private final String bucket;
    private volatile boolean bucketReady;

    public MinioObjectStorage(@Value("${lingxi.kb.minio.endpoint}") String endpoint,
                              @Value("${lingxi.kb.minio.access-key}") String accessKey,
                              @Value("${lingxi.kb.minio.secret-key}") String secretKey,
                              @Value("${lingxi.kb.minio.bucket}") String bucket) {
        this.client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        this.bucket = bucket;
        try {
            ensureBucket();
        } catch (Exception e) {
            log.warn("MinIO 存储桶初始化失败（首次读写时将重试）bucket={}", bucket, e);
        }
    }

    @Override
    public void put(String objectKey, InputStream stream, long sizeBytes, String contentType) {
        ensureBucketReady();
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(stream, sizeBytes, -1)
                    .contentType(contentType == null || contentType.isBlank()
                            ? "application/octet-stream" : contentType)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("对象上传失败: " + objectKey, e);
        }
    }

    @Override
    public String presignedGetUrl(String objectKey, int expirySeconds) {
        ensureBucketReady();
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(expirySeconds, TimeUnit.SECONDS)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("预签名 URL 生成失败: " + objectKey, e);
        }
    }

    @Override
    public InputStream get(String objectKey) {
        ensureBucketReady();
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            throw new IllegalStateException("对象读取失败: " + objectKey, e);
        }
    }

    @Override
    public void remove(String objectKey) {
        ensureBucketReady();
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            // 删除失败不阻断主流程：残留对象由后台清理任务兜底
            log.warn("对象删除失败: {}", objectKey, e);
        }
    }

    private void ensureBucketReady() {
        if (!bucketReady) {
            ensureBucket();
        }
    }

    private synchronized void ensureBucket() {
        if (bucketReady) {
            return;
        }
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("MinIO 存储桶已创建: {}", bucket);
            }
            bucketReady = true;
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 存储桶不可用: " + bucket, e);
        }
    }
}
