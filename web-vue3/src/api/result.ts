/**
 * Java Result 信封解包工具。
 *
 * 网关存在两类响应体：
 * - 管理类接口（auth / users / logs）返回 Java 的 Result 信封 `{code, message, data}`；
 * - 既有业务接口（report / studio / agent / session）返回裸 JSON。
 *
 * 之所以在这里集中解包：http 拦截器已把 axios 响应解包为「响应体本身」，
 * 各 api 只需调用本函数，即可对两类响应统一处理，无需在每处重复判空 code。
 */
export function unwrapResult<T>(body: unknown): T {
  if (body && typeof body === 'object' && 'code' in body) {
    const envelope = body as { code: number; message?: string; data?: T }
    if (envelope.code === 0) {
      return envelope.data as T
    }
    throw new Error(envelope.message || '请求失败')
  }
  // 无 code 字段的裸 JSON：原样返回
  return body as T
}

/** 分页结构（users / logs 共用）。 */
export interface PageResult<T> {
  total: number
  page: number
  size: number
  items: T[]
}
