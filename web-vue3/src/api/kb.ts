import http from './http'
import { unwrapResult } from './result'

/**
 * 知识库运营 API：对接网关 /api/v1/kb/**（以 admin 权限转发到 Java 编排域）。
 * 响应为 Java 的 Result 信封，统一经 unwrapResult 解包。
 *
 * 之所以把分片上传编排也收在本模块：分片涉及 init → 逐片 → complete 多端点按序调用，
 * 收敛于此视图层只需表达「传哪个文件」，不必关心分片协议细节。
 */

/** 知识库状态。 */
export type KbStatus = 'ENABLED' | 'DISABLED'

/** 文档切片状态：待入库 / 已入库 / 失败 / 已下线。 */
export type DocStatus = 'PENDING' | 'INDEXED' | 'FAILED' | 'DELETED'

/** 知识库视图。 */
export interface KbView {
  kbId: string
  tenantId: string
  name: string
  description: string
  status: KbStatus
  createdAt: string
  updatedAt: string
}

/** 文档分类视图。 */
export interface CategoryView {
  categoryId: string
  kbId: string
  tenantId: string
  name: string
  createdAt: string
  updatedAt: string
}

/** 文档视图（切片进度与入库结果）。 */
export interface DocumentView {
  docId: string
  kbId: string
  tenantId: string
  /** 所属分类 ID，未归类时可能为空串 */
  categoryId: string
  title: string
  fileName: string
  docType: string
  version: number
  sizeBytes: number
  /** 逗号分隔的标签串 */
  tags: string
  status: DocStatus
  /** 失败原因，status=FAILED 时才有值 */
  statusMessage: string
  /** 切片后的子块数（召回命中块） */
  childCount: number
  /** 父块数（父子分块结构下的父级块） */
  parentCount: number
  createdAt: string
  updatedAt: string
}

/** 分片上传会话视图。 */
export interface UploadSessionView {
  uploadId: string
  fileName: string
  totalParts: number
  partSizeBytes: number
  /** 服务端已接收的分片号（续传时可用于跳过） */
  receivedParts: number[]
  status: string
}

/** 单请求上传参数（docId 表示作为该文档的新版本覆盖）。 */
export interface UploadDocumentParams {
  kbId: string
  file: File
  docId?: string
  title?: string
  categoryId?: string
  tags?: string
}

/** 分片上传初始化参数。 */
export interface InitUploadParams {
  kbId: string
  fileName: string
  totalParts: number
  docId?: string
  title?: string
  categoryId?: string
  tags?: string
}

/** 文档查询参数（status 可省，缺省返回全部状态）。 */
export interface DocumentQuery {
  kbId: string
  status?: DocStatus
}

/** 单请求上传的体积上限（4MB）：与后端 partSizeBytes 约定保持一致。 */
export const SINGLE_UPLOAD_LIMIT_BYTES = 4 * 1024 * 1024

/** 列出全部知识库。 */
export async function listBases(): Promise<KbView[]> {
  const body = await http.get('/kb/bases')
  return unwrapResult<KbView[]>(body)
}

/** 新建知识库（不传 tenantId，租户由服务端按登录身份解析）。 */
export async function createBase(payload: { name: string; description?: string }): Promise<KbView> {
  const body = await http.post('/kb/bases', payload)
  return unwrapResult<KbView>(body)
}

/** 修改知识库名称 / 描述。 */
export async function updateBase(
  kbId: string,
  payload: { name: string; description?: string },
): Promise<KbView> {
  const body = await http.put(`/kb/bases/${kbId}`, payload)
  return unwrapResult<KbView>(body)
}

/** 启用 / 停用知识库。 */
export async function updateBaseStatus(kbId: string, status: KbStatus): Promise<KbView> {
  const body = await http.post(`/kb/bases/${kbId}/status`, { status })
  return unwrapResult<KbView>(body)
}

/** 列出知识库下的分类。 */
export async function listCategories(kbId: string): Promise<CategoryView[]> {
  const body = await http.get(`/kb/bases/${kbId}/categories`)
  return unwrapResult<CategoryView[]>(body)
}

/** 新建分类。 */
export async function createCategory(kbId: string, name: string): Promise<CategoryView> {
  const body = await http.post(`/kb/bases/${kbId}/categories`, { name })
  return unwrapResult<CategoryView>(body)
}

/** 删除分类。 */
export async function deleteCategory(categoryId: string): Promise<void> {
  const body = await http.delete(`/kb/bases/categories/${categoryId}`)
  unwrapResult<unknown>(body)
}

/** 列出知识库下的文档。 */
export async function listDocuments(query: DocumentQuery): Promise<DocumentView[]> {
  // status 为 undefined 时 axios 会自动省略该查询参数
  const body = await http.get('/kb/documents', {
    params: { kbId: query.kbId, status: query.status },
  })
  return unwrapResult<DocumentView[]>(body)
}

/**
 * 单请求上传（适用于小文件）。
 * multipart 由 axios 依据 FormData 自动补 Content-Type 与 boundary，故此处不手工设置。
 */
export async function uploadDocument(params: UploadDocumentParams): Promise<DocumentView> {
  const form = new FormData()
  form.append('file', params.file)
  const body = await http.post('/kb/documents', form, {
    params: {
      kbId: params.kbId,
      docId: params.docId,
      title: params.title,
      categoryId: params.categoryId,
      tags: params.tags,
    },
  })
  return unwrapResult<DocumentView>(body)
}

/** 初始化分片上传会话（JSON，返回分片大小与已接收分片）。 */
export async function initUpload(params: InitUploadParams): Promise<UploadSessionView> {
  const body = await http.post('/kb/documents/uploads', {
    kbId: params.kbId,
    docId: params.docId,
    title: params.title,
    categoryId: params.categoryId,
    tags: params.tags,
    fileName: params.fileName,
    totalParts: params.totalParts,
  })
  return unwrapResult<UploadSessionView>(body)
}

/** 上传单个分片（partNumber 从 1 起）。 */
export async function uploadPart(uploadId: string, partNumber: number, blob: Blob): Promise<void> {
  const form = new FormData()
  form.append('file', blob)
  const body = await http.post(`/kb/documents/uploads/${uploadId}/parts`, form, {
    params: { partNumber },
  })
  unwrapResult<unknown>(body)
}

/** 完成分片上传：服务端合并分片并触发入库，返回最终文档。 */
export async function completeUpload(uploadId: string): Promise<DocumentView> {
  const body = await http.post(`/kb/documents/uploads/${uploadId}/complete`)
  return unwrapResult<DocumentView>(body)
}

/** 中止分片上传（清理服务端会话）。 */
export async function abortUpload(uploadId: string): Promise<void> {
  const body = await http.delete(`/kb/documents/uploads/${uploadId}`)
  unwrapResult<unknown>(body)
}

/** 查询单个文档详情。 */
export async function getDocument(docId: string): Promise<DocumentView> {
  const body = await http.get(`/kb/documents/${docId}`)
  return unwrapResult<DocumentView>(body)
}

/** 下线文档（逻辑删除）并返回最新文档。 */
export async function deleteDocument(docId: string): Promise<DocumentView> {
  const body = await http.delete(`/kb/documents/${docId}`)
  return unwrapResult<DocumentView>(body)
}

/**
 * 自动上传：≤4MB 单请求直传；>4MB 按 4MB 切分走 init → 逐片 → complete。
 * onProgress 回传上传百分比（0~100）供视图展示。
 * 任一步失败即中止会话（清理半截分片，避免占用存储）并向上抛出，由调用方提示。
 */
export async function uploadDocumentAuto(
  params: UploadDocumentParams,
  onProgress?: (percent: number) => void,
): Promise<DocumentView> {
  if (params.file.size <= SINGLE_UPLOAD_LIMIT_BYTES) {
    onProgress?.(0)
    const document = await uploadDocument(params)
    onProgress?.(100)
    return document
  }

  const totalParts = Math.ceil(params.file.size / SINGLE_UPLOAD_LIMIT_BYTES)
  const session = await initUpload({
    kbId: params.kbId,
    fileName: params.file.name,
    totalParts,
    docId: params.docId,
    title: params.title,
    categoryId: params.categoryId,
    tags: params.tags,
  })
  try {
    for (let partNumber = 1; partNumber <= totalParts; partNumber += 1) {
      const start = (partNumber - 1) * SINGLE_UPLOAD_LIMIT_BYTES
      const blob = params.file.slice(start, start + SINGLE_UPLOAD_LIMIT_BYTES)
      await uploadPart(session.uploadId, partNumber, blob)
      onProgress?.(Math.round((partNumber / totalParts) * 100))
    }
    return await completeUpload(session.uploadId)
  } catch (error) {
    try {
      await abortUpload(session.uploadId)
    } catch {
      // 中止失败不覆盖原始错误：调用方更关心真正失败的原因
    }
    throw error
  }
}
