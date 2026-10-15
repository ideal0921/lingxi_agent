import http from './http'

/**
 * 客服工作台 API：对接 Go 网关 /api/v1/agent/*（网关转发 Java 会话域与工单域）。
 * 约定：http 响应拦截器已解包为响应体，此处按业务结构断言类型。
 */

/** 会话摘要（队列/坐席会话列表）。 */
export interface SessionSummary {
  session_id: string
  user_id: string
  state: 'INIT' | 'AI_ACTIVE' | 'HUMAN_CHAT' | 'CLOSED' | string
  agent_id: string
  created_at_ms: number
  updated_at_ms: number
  message_count: number
  /** 上下文摘要预览（M4-4.5 摘要移交；服务端截断后下发，可空） */
  summary?: string
}

/** 工单状态。 */
export type TicketStatus = 'CREATED' | 'PROCESSING' | 'CLOSED'

/** 工单。 */
export interface Ticket {
  ticket_id: string
  tenant_id: string
  session_id: string
  user_id: string
  agent_id: string
  title: string
  description: string
  status: TicketStatus
  created_at_ms: number
  updated_at_ms: number
}

/** 接管结果。 */
export interface TakeOverResult {
  ok: boolean
  reason: string
  state: string
}

/** 工单流转结果。 */
export interface TicketTransitionResult {
  ok: boolean
  reason: string
  ticket: Ticket
}

/** 回复建议结果（M4-4.6 同步通道）。 */
export interface SuggestRepliesResult {
  /** 三端贯穿的请求标识，用于同步/异步结果去重。 */
  request_id: string
  /** 是否成功产出候选（degraded 且 queued 时为 false）。 */
  ok: boolean
  /** 是否降级（同步通道过载/超时）。 */
  degraded: boolean
  /** 降级原因：BUSY / TIMEOUT / UNAVAILABLE。 */
  degrade_reason: string
  /** 是否已转异步队列（true 时结果稍后经坐席端 WSS 推送）。 */
  queued: boolean
  /** 候选回复（1~3 条，供坐席选择/编辑）。 */
  suggestions: string[]
  /** 本次同步耗时。 */
  elapsed_ms: number
}

/** 客服队列：待接管会话列表。 */
export async function listQueue(limit = 20): Promise<SessionSummary[]> {
  const resp = (await http.get('/agent/queue', { params: { limit } })) as {
    sessions: SessionSummary[]
  }
  return resp.sessions ?? []
}

/** 指定坐席的会话列表。 */
export async function listAgentSessions(agentId: string): Promise<SessionSummary[]> {
  const resp = (await http.get('/agent/sessions', { params: { agent_id: agentId } })) as {
    sessions: SessionSummary[]
  }
  return resp.sessions ?? []
}

/** 客服接管（抢单）：并发下仅一个坐席成功。 */
export async function takeOver(sessionId: string, agentId: string): Promise<TakeOverResult> {
  return (await http.post('/agent/takeover', {
    session_id: sessionId,
    agent_id: agentId,
  })) as TakeOverResult
}

/** 坐席回复：落库会话域后经 WSS 广播下行给用户。 */
export async function agentReply(
  sessionId: string,
  content: string,
  agentId: string,
): Promise<{ seq: number; message_id: string }> {
  return (await http.post('/agent/reply', {
    session_id: sessionId,
    content,
    agent_id: agentId,
  })) as { seq: number; message_id: string }
}

/**
 * 快捷回复建议：调 Java 同步通道取 1~3 条候选草稿。
 * 过载时服务端可能返回 degraded+queued（已转异步），结果稍后经坐席端 WSS 推送。
 */
export async function suggestReplies(payload: {
  session_id: string
  agent_id: string
  intent?: string
  max_suggestions?: number
}): Promise<SuggestRepliesResult> {
  return (await http.post('/agent/suggest', payload)) as SuggestRepliesResult
}

/** 一键建单。 */
export async function createTicket(payload: {
  tenant_id: string
  session_id: string
  user_id: string
  agent_id: string
  title: string
  description: string
}): Promise<Ticket> {
  const resp = (await http.post('/agent/tickets', payload)) as { ticket: Ticket }
  return resp.ticket
}

/** 工单列表（agentId 或 status 过滤）。 */
export async function listTickets(agentId?: string, status?: TicketStatus): Promise<Ticket[]> {
  const resp = (await http.get('/agent/tickets', {
    params: { agent_id: agentId, status },
  })) as { tickets: Ticket[] }
  return resp.tickets ?? []
}

/** 工单状态流转（非法流转返回 ok=false + reason）。 */
export async function updateTicketStatus(
  ticketId: string,
  status: TicketStatus,
  operator: string,
): Promise<TicketTransitionResult> {
  return (await http.post(`/agent/tickets/${ticketId}/status`, {
    status,
    operator,
  })) as TicketTransitionResult
}
