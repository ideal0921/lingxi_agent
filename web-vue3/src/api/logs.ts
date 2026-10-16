import http from './http'
import { unwrapResult, type PageResult } from './result'

/**
 * 操作日志 API：对接 /api/v1/logs（需 admin）。
 * 响应为 Result 信封，统一经 unwrapResult 解包。
 */

/** 操作日志视图。 */
export interface OperationLogView {
  logId: string
  tenantId: string
  userId: string
  username: string
  role: string
  module: string
  action: string
  resourceType: string
  resourceId: string
  /** SUCCESS | FAILED | DENIED */
  result: string
  detail: string
  clientIp: string
  userAgent: string
  elapsedMs: number
  createdAtMs: number
}

/** 日志查询参数（from/to 为 epoch 毫秒，缺省由服务端取默认窗口）。 */
export interface LogQuery {
  module?: string
  action?: string
  result?: string
  from?: number
  to?: number
  page?: number
  size?: number
}

/** 分页查询操作日志。 */
export async function listLogs(query: LogQuery = {}): Promise<PageResult<OperationLogView>> {
  const body = await http.get('/logs', { params: query })
  return unwrapResult<PageResult<OperationLogView>>(body)
}
