import http from './http'

/**
 * 运营报表 API：对接 Go 网关 /api/v1/reports/*（网关转发 Java 报表域）。
 * 约定：http 响应拦截器已解包为响应体，此处按业务结构断言类型。
 */

/** 漏斗阶段（相邻转化率 step_rate、相对建会话的累计转化率 total_rate）。 */
export interface FunnelStage {
  stage: string
  label: string
  sessions: number
  step_rate: number
  total_rate: number
}

/** 会话漏斗报表：created_sessions 为分母基数（区间内建会话总数）。 */
export interface FunnelReport {
  created_sessions: number
  stages: FunnelStage[]
}

/** Token 消耗明细行（日 × 提供方 × 模型）。 */
export interface TokenUsageRow {
  stat_date: string
  provider: string
  model: string
  requests: number
  prompt_tokens: number
  completion_tokens: number
  /** 其中无法取得真实 usage 的估算条数（用于展示估算占比） */
  estimated_requests: number
  first_token_ms_avg: number
}

/** Token 消耗报表：合计 + 明细。 */
export interface TokenReport {
  total_requests: number
  total_prompt_tokens: number
  total_completion_tokens: number
  total_estimated_requests: number
  rows: TokenUsageRow[]
}

/** 评分分布条目（按 5→1 星降序）。 */
export interface RatingBucket {
  rating: number
  count: number
}

/** 评价明细（最近 N 条）。 */
export interface FeedbackItem {
  session_id: string
  rating: number
  comment: string
  created_at_ms: number
}

/** 满意度报表：CSAT = 评分 ≥4 的评价数 / 评价总数。 */
export interface CsatReport {
  feedback_count: number
  average_rating: number
  csat: number
  distribution: RatingBucket[]
  recent: FeedbackItem[]
}

/** 报表查询区间：tenantId 必填；from/to 省略时由服务端套用最近 7 天。 */
export interface ReportQuery {
  tenantId: string
  fromMs?: number
  toMs?: number
}

/** 组装查询参数（from/to 为 epoch 毫秒，省略即由服务端取默认窗口）。 */
function rangeParams(query: ReportQuery): Record<string, string | number> {
  const params: Record<string, string | number> = { tenant_id: query.tenantId }
  if (query.fromMs) params.from = query.fromMs
  if (query.toMs) params.to = query.toMs
  return params
}

/** 会话漏斗：各阶段会话数 + 相邻/累计转化率。 */
export async function getFunnelReport(query: ReportQuery): Promise<FunnelReport> {
  return (await http.get('/reports/funnel', { params: rangeParams(query) })) as FunnelReport
}

/** Token 消耗：日 × 提供方 × 模型 明细 + 合计。 */
export async function getTokenReport(query: ReportQuery): Promise<TokenReport> {
  return (await http.get('/reports/tokens', { params: rangeParams(query) })) as TokenReport
}

/** 满意度：评价数 / 平均分 / CSAT / 评分分布 / 最近评价。 */
export async function getCsatReport(query: ReportQuery): Promise<CsatReport> {
  return (await http.get('/reports/csat', { params: rangeParams(query) })) as CsatReport
}
