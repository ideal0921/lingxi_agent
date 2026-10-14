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
