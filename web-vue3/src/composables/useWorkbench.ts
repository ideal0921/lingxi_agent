import { ref, onUnmounted } from 'vue'
import {
  listQueue,
  listAgentSessions,
  takeOver,
  agentReply,
  suggestReplies,
  createTicket,
  listTickets,
  updateTicketStatus,
  type SessionSummary,
  type Ticket,
  type TicketStatus,
} from '../api/agent'
import { WssClient, type ChatMessage, type WsStatus } from '../api/wss'

/** 队列轮询间隔（ms）：兼顾实时性与服务端压力。 */
const QUEUE_POLL_INTERVAL = 5000

const AGENT_KEY = 'lingxi_agent_id'

/**
 * 客服工作台状态管理：
 * - 队列轮询 + 坐席会话列表；
 * - 选中会话后以 WSS 观察端接入（同会话多连接，不顶替用户），resume(0) 回源历史；
 * - 回复走 HTTP（/agent/reply）落库后由网关广播，工作台通过 WSS 收到权威消息；
 * - 工单：一键建单 + 状态流转。
 */
export function useWorkbench() {
  // 坐席标识：本地持久化，联调环境固定。
  let agentId = localStorage.getItem(AGENT_KEY) ?? 'agent-01'
  localStorage.setItem(AGENT_KEY, agentId)

  const queue = ref<SessionSummary[]>([])
  const mySessions = ref<SessionSummary[]>([])
  const selected = ref<SessionSummary | null>(null)
  const messages = ref<ChatMessage[]>([])
  const tickets = ref<Ticket[]>([])
  const wsStatus = ref<WsStatus>('disconnected')
  const error = ref('')

  // 回复草稿：坐席手动输入或应用「快捷回复」候选后编辑发送。
  const draft = ref('')
  // 快捷回复建议（M4-4.6）：候选列表、请求中态与降级提示。
  const suggestions = ref<string[]>([])
  const suggesting = ref(false)
  const suggestNotice = ref('')
  // 已同步返回的 request_id：异步推送同 id 时忽略，避免重复填充。
  const seenRequestIds = new Set<string>()
  // 最近一次在途请求标识。
  let pendingRequestId = ''

  let observer: WssClient | null = null
  let pollTimer: number | null = null

  /** 启动工作台：加载列表并开启轮询。 */
  async function init(): Promise<void> {
    await refresh()
    pollTimer = window.setInterval(() => void refresh(), QUEUE_POLL_INTERVAL)
  }

  /** 刷新队列与坐席会话列表。 */
  async function refresh(): Promise<void> {
    try {
      const [waiting, mine] = await Promise.all([listQueue(), listAgentSessions(agentId)])
      queue.value = waiting
      mySessions.value = mine
    } catch {
      error.value = '刷新会话列表失败'
    }
  }

  /** 接管会话并选中观察。 */
  async function takeOverSession(session: SessionSummary): Promise<void> {
    try {
      const result = await takeOver(session.session_id, agentId)
      if (!result.ok) {
        error.value = result.reason || '接管失败'
        return
      }
      error.value = ''
      await refresh()
      const taken = mySessions.value.find((s) => s.session_id === session.session_id)
      selectSession(taken ?? { ...session, state: result.state, agent_id: agentId })
    } catch {
      error.value = '接管请求失败'
    }
  }

  /** 选中会话：以 WSS 观察端接入，回源历史消息。 */
  function selectSession(session: SessionSummary): void {
    selected.value = session
    messages.value = []
    // 会话切换：重置回复草稿与快捷回复相关状态，避免串会话。
    draft.value = ''
    suggestions.value = []
    suggestNotice.value = ''
    suggesting.value = false
    pendingRequestId = ''
    observer?.disconnect()
    // role='agent'：坐席观察端，可接收「仅推坐席」下行（如快捷回复建议）。
    observer = new WssClient(
      session.session_id,
      agentId,
      (ev) => {
        switch (ev.type) {
          case 'status':
            wsStatus.value = ev.status
            break
          case 'chat':
            messages.value.push(ev.message)
            break
          case 'suggest':
            // 同步已返回过的 request_id → 忽略（防重复）；否则填充异步候选。
            if (seenRequestIds.has(ev.requestId)) break
            suggestions.value = ev.suggestions
            suggestNotice.value = ''
            if (ev.requestId === pendingRequestId) pendingRequestId = ''
            break
          case 'error':
            error.value = ev.reason
            break
          default:
            break
        }
      },
      'agent',
    )
    observer.resumeFrom(0)
    observer.connect()
    void loadTickets(session)
  }

  /** 发送客服回复。 */
  async function sendReply(content: string): Promise<void> {
    const session = selected.value
    const text = content.trim()
    if (!session || !text) return
    try {
      await agentReply(session.session_id, text, agentId)
      // 消息以网关广播的 chat 信封为准，此处不做乐观渲染，避免重复。
      await refresh()
    } catch {
      error.value = '消息发送失败，请重试'
    }
  }

  /**
   * 快捷回复：请求同步建议（1~3 条候选草稿）。
   * - ok 且有候选 → 填充候选并记录 request_id（异步同 id 推送将被忽略）；
   * - degraded+queued → 清空候选，提示结果稍后经坐席端 WSS 推送；
   * - 其余降级/失败 → 提示重试。
   */
  async function requestSuggest(intent?: string): Promise<void> {
    const session = selected.value
    if (!session || suggesting.value) return
    suggesting.value = true
    suggestNotice.value = ''
    try {
      const result = await suggestReplies({
        session_id: session.session_id,
        agent_id: agentId,
        intent,
      })
      pendingRequestId = result.request_id
      if (result.ok && result.suggestions.length > 0) {
        suggestions.value = result.suggestions
        seenRequestIds.add(result.request_id)
      } else if (result.degraded && result.queued) {
        suggestions.value = []
        suggestNotice.value = '已转异步排队，结果稍后推送'
      } else {
        suggestions.value = []
        suggestNotice.value = '建议生成失败，请重试'
      }
    } catch {
      suggestions.value = []
      suggestNotice.value = '建议生成失败，请重试'
    } finally {
      suggesting.value = false
    }
  }

  /** 应用候选：写入回复草稿，供坐席编辑后 sendReply。 */
  function applySuggestion(text: string): void {
    draft.value = text
  }

  /** 一键建单（关联当前会话与用户）。 */
  async function createTicketForSession(title: string, description: string): Promise<boolean> {
    const session = selected.value
    if (!session || !title.trim()) return false
    try {
      await createTicket({
        tenant_id: 'lingxi',
        session_id: session.session_id,
        user_id: session.user_id,
        agent_id: agentId,
        title: title.trim(),
        description: description.trim(),
      })
      await loadTickets(session)
      return true
    } catch {
      error.value = '建单失败'
      return false
    }
  }

  /** 加载当前会话用户的工单。 */
  async function loadTickets(session: SessionSummary): Promise<void> {
    try {
      const all = await listTickets()
      tickets.value = all.filter((t) => t.session_id === session.session_id)
    } catch {
      error.value = '加载工单失败'
    }
  }

  /** 工单状态流转。 */
  async function transitionTicket(ticketId: string, status: TicketStatus): Promise<void> {
    try {
      const result = await updateTicketStatus(ticketId, status, agentId)
      if (!result.ok) {
        error.value = result.reason || '工单流转失败'
        return
      }
      if (selected.value) await loadTickets(selected.value)
    } catch {
      error.value = '工单流转请求失败'
    }
  }

  onUnmounted(() => {
    observer?.disconnect()
    if (pollTimer !== null) window.clearInterval(pollTimer)
  })

  return {
    agentId,
    queue,
    mySessions,
    selected,
    messages,
    tickets,
    wsStatus,
    error,
    draft,
    suggestions,
    suggesting,
    suggestNotice,
    init,
    refresh,
    takeOverSession,
    selectSession,
    sendReply,
    requestSuggest,
    applySuggestion,
    createTicketForSession,
    transitionTicket,
  }
}
