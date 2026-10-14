package com.lingxi.knowledge.api;

import com.lingxi.common.exception.BizException;
import com.lingxi.common.result.Result;
import com.lingxi.knowledge.api.dto.KnowledgeDtos.DocumentView;
import com.lingxi.knowledge.api.dto.KnowledgeDtos.InitUploadRequest;
import com.lingxi.knowledge.api.dto.KnowledgeDtos.UploadSessionView;
import com.lingxi.knowledge.application.DocumentService;
import com.lingxi.knowledge.domain.DocumentStatus;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 文档管理接口：上传（单请求 / 分片）、列表、切片状态查询、下线。
 *
 * <p>单请求上传适合小文件；超大文档走「初始化 → 逐片上传 → 合并」三段式，
 * 分片可乱序与重传（断点续传）。</p>
 */
@RestController
@RequestMapping("/api/v1/kb/documents")
public class DocumentController {

    private static final int ERR_BAD_STATUS = 40004;
    private static final int ERR_READ_FILE = 40005;

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    /** 单请求上传文档（小文件）；传 docId 时作为新版本覆盖。 */
    @PostMapping
    public Result<DocumentView> upload(@RequestParam String kbId,
                                       @RequestParam String tenantId,
                                       @RequestParam(required = false) String docId,
                                       @RequestParam(required = false) String title,
                                       @RequestParam(required = false) String categoryId,
                                       @RequestParam(required = false) String tags,
                                       @RequestPart("file") MultipartFile file) {
        try (InputStream content = file.getInputStream()) {
            return Result.ok(DocumentView.from(documentService.upload(
                    kbId, tenantId, docId, title, categoryId, tags, fileNameOf(file),
                    file.getContentType(), content, file.getSize())));
        } catch (IOException e) {
            throw new BizException(ERR_READ_FILE, "读取上传文件失败：" + e.getMessage());
        }
    }

    /** 按知识库列出文档（可按切片状态过滤）。 */
    @GetMapping
    public Result<List<DocumentView>> list(@RequestParam String kbId,
                                           @RequestParam String tenantId,
                                           @RequestParam(required = false) String status) {
        DocumentStatus documentStatus = status == null || status.isBlank() ? null : parseStatus(status);
        return Result.ok(documentService.listDocuments(kbId, tenantId, documentStatus)
                .stream().map(DocumentView::from).toList());
    }

    /** 查询文档（含切片状态、子块/父块数量）。 */
    @GetMapping("/{docId}")
    public Result<DocumentView> get(@PathVariable String docId, @RequestParam String tenantId) {
        return Result.ok(DocumentView.from(documentService.getDocument(docId, tenantId)));
    }

    /** 下线文档（逻辑删除）。 */
    @DeleteMapping("/{docId}")
    public Result<DocumentView> delete(@PathVariable String docId, @RequestParam String tenantId) {
        return Result.ok(DocumentView.from(documentService.deleteDocument(docId, tenantId)));
    }

    /** 初始化分片上传会话。 */
    @PostMapping("/uploads")
    public Result<UploadSessionView> initUpload(@Valid @RequestBody InitUploadRequest request) {
        return Result.ok(UploadSessionView.from(documentService.initUpload(
                request.kbId(), request.tenantId(), request.docId(), request.title(),
                request.categoryId(), request.tags(), request.fileName(), request.totalParts())));
    }

    /** 上传单个分片（可乱序、可重传）。 */
    @PostMapping("/uploads/{uploadId}/parts")
    public Result<UploadSessionView> uploadPart(@PathVariable String uploadId,
                                                @RequestParam int partNumber,
                                                @RequestPart("file") MultipartFile file) {
        try (InputStream content = file.getInputStream()) {
            return Result.ok(UploadSessionView.from(
                    documentService.uploadPart(uploadId, partNumber, content, file.getSize())));
        } catch (IOException e) {
            throw new BizException(ERR_READ_FILE, "读取分片失败：" + e.getMessage());
        }
    }

    /** 合并分片并触发入库（分片齐全为前提）。 */
    @PostMapping("/uploads/{uploadId}/complete")
    public Result<DocumentView> completeUpload(@PathVariable String uploadId) {
        return Result.ok(DocumentView.from(documentService.completeUpload(uploadId)));
    }

    /** 中止分片上传并清理分片。 */
    @DeleteMapping("/uploads/{uploadId}")
    public Result<Void> abortUpload(@PathVariable String uploadId) {
        documentService.abortUpload(uploadId);
        return Result.ok();
    }

    private static String fileNameOf(MultipartFile file) {
        String name = file.getOriginalFilename();
        return name == null || name.isBlank() ? "unnamed" : name;
    }

    private static DocumentStatus parseStatus(String status) {
        try {
            return DocumentStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException(ERR_BAD_STATUS, "非法的文档状态：" + status);
        }
    }
}
