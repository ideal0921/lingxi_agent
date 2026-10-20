import http from './http'
import { unwrapResult, type PageResult } from './result'

/**
 * API 接口链路追踪 API：对接 /api/v1/traces（需 admin）。
 * 响应为 Result 信封，统一经 unwrapResult 解包。
 */

/** 链路 Span 视图（字段与后端 ApiTraceSpanView 完全对齐）。 */
export interface ApiTraceSpanView {
  spanId: string
  traceId: string
  /** 上游 Span（用于还原调用关系） */
  parentSpanId: string
  /** 来源服务：gateway-go | core-java | ai-python */
  service: string
  /** 调用类型：HTTP | GRPC | KAFKA | WSS */
  component: string
  /** 入口标识：HTTP 路径 / gRPC 方法 / Kafka topic */
  entry: string
  httpMethod: string
  /** 结果：OK | ERROR */
  status: string
  statusCode: number
  tenantId: string
  userId: string
  role: string
  clientIp: string
  elapsedMs: number
  errorMsg: string
  /** 开始时间（epoch 毫秒） */
  startedAtMs: number
}

/**
 * 链路查询参数（from/to 为 epoch 毫秒）。
 * 不传 tenant_id：租户由后端按当前登录身份强制过滤。
 */
export interface TraceQuery {
  traceId?: string
  entry?: string
  status?: string
  from?: number
  to?: number
  page?: number
  size?: number
}

/** 分页查询链路 Span 列表。 */
export async function listTraces(query: TraceQuery = {}): Promise<PageResult<ApiTraceSpanView>> {
  const body = await http.get('/traces', { params: query })
  return unwrapResult<PageResult<ApiTraceSpanView>>(body)
}

/** 查询单条链路的全部 Span（后端按 startedAtMs 升序返回，用于时间线）。 */
export async function getTrace(traceId: string): Promise<ApiTraceSpanView[]> {
  const body = await http.get(`/traces/${encodeURIComponent(traceId)}`)
  return unwrapResult<ApiTraceSpanView[]>(body)
}
