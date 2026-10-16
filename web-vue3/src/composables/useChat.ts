import { ref, onUnmounted } from 'vue'
import {
  WssClient,
  createSession,
  listSessions,
  submitFeedback,
  type ChatMessage,
  type SessionListItem,
  type WsStatus,
} from '../api/wss'
import { useAuthStore } from '../stores/auth'

const SESSION_KEY = 'lingxi_portal_session'
/** 左侧会话列表一次拉取的条数（服务端另有上限收敛）。 */
const SESSION_LIST_LIMIT = 20

/**
 * 用户端会话状态管理：
 * - 当前会话 ID 持久化到 sessionStorage（刷新页面复用，避免重复建会话）；
 * - WSS 断连自动重连 + resume 续传；
 * - 本地乐观渲染用户消息，坐席消息由服务端 chat 信封驱动；
 * - 历史会话列表（服务端权威）+ 点击切换（切会话后回源拉取该会话历史）。
 */
export function useChat() {
  const messages = ref<ChatMessage[]>([])
  const status = ref<WsStatus>('disconnected')
  const error = ref('')
  // 满意度评价（M5-5.1）：提交成功置为已评价态，失败仅提示（不影响会话）
  const rating = ref(0)
  const feedbackSubmitted = ref(false)
  const feedbackError = ref('')
  /** 左侧历史会话列表（服务端按最近活跃倒序）。 */
  const sessions = ref<SessionListItem[]>([])
  /** 会话列表加载失败提示（与通道 error 分开，互不干扰）。 */
  const sessionsError = ref('')

  let client: WssClient | null = null
  let currentUserId = ''
  /** 当前会话 ID：响应式（供左侧列表高亮当前项）。 */
  const sessionId = ref(sessionStorage.getItem(SESSION_KEY) ?? '')

  async function init(userId: string): Promise<void> {
    currentUserId = userId
    if (!sessionId.value) {
      sessionId.value = await createSession(userId)
      sessionStorage.setItem(SESSION_KEY, sessionId.value)
    }
    connect()
    // 刷新页面后从会话域回源补齐历史消息。
    client?.resumeFrom(0)
    void loadSessions()
  }

  /** 拉取历史会话列表；失败只提示，不影响当前会话收发。 */
  async function loadSessions(): Promise<void> {
    try {
      sessions.value = await listSessions(SESSION_LIST_LIMIT)
      sessionsError.value = ''
    } catch (e) {
      sessionsError.value = (e as Error).message || '会话列表加载失败'
    }
  }

  function connect(): void {
    client?.disconnect()
    // 令牌经查询参数传递（WebSocket 无法自定义请求头），来源为登录态
    const token = useAuthStore().token
    client = new WssClient(sessionId.value, currentUserId, (ev) => {
      switch (ev.type) {
        case 'status':
          status.value = ev.status
          break
        case 'chat':
          mergeMessage(ev.message)
          break
        case 'chunk':
          applyChunk(ev.messageId, ev.delta, ev.finished, ev.error, ev.interrupted)
          // 回答收尾后刷新列表：预览与最近活跃时间需要同步（列表数据量小，代价可接受）
          if (ev.finished) void loadSessions()
          break
        case 'ack':
          markSent(ev.messageId, ev.seq)
          void loadSessions()
          break
        case 'error':
          error.value = ev.reason
          break
      }
    }, 'user', token)
    client.connect()
  }

  /**
   * 合并落库消息：
   * - 已存在同 ID 的消息（流式打字机内容）→ 就地更新为权威内容与 seq；
   * - 否则追加为新消息。
   * 这一步保证「流式内容」与「Java 落库消息」不会重复渲染。
   */
  function mergeMessage(incoming: ChatMessage): void {
    const existing = messages.value.find((m) => m.id && m.id === incoming.id)
    if (existing) {
      existing.content = incoming.content
      existing.seq = incoming.seq
      existing.streaming = false
      return
    }
    messages.value.push(incoming)
  }

  /**
   * 应用流式片段（打字机）：首帧创建助手消息，后续按序累加；终帧结束打字机态。
   * interrupted 为真表示本轮因人工接管被中断，与 finished 一致地结束打字机态，
   * 并在消息上标记 interrupted（正常业务态，不写入 error）。
   */
  function applyChunk(
    messageId: string,
    delta: string,
    finished: boolean,
    chunkError: string,
    interrupted = false,
  ): void {
    if (!messageId) return
    let target = messages.value.find((m) => m.id === messageId)
    if (!target && !finished && !interrupted) {
      target = {
        id: messageId,
        sessionId: sessionId.value,
        sender: 'agent',
        content: '',
        seq: 0,
        timestamp: Date.now(),
        streaming: true,
      }
      messages.value.push(target)
    }
    if (target) {
      if (delta) target.content += delta
      if (finished || interrupted) target.streaming = false
      if (interrupted) target.interrupted = true
    }
    if (finished && chunkError) error.value = chunkError
  }

  /** 发送消息：乐观本地渲染，ack 到达后回填 messageId/seq。 */
  function send(content: string): void {
    if (!client?.connected) {
      error.value = '连接未就绪，请稍候重试'
      return
    }
    const text = content.trim()
    if (!text) return
    const optimistic: ChatMessage = {
      id: `local-${Date.now()}`,
      sessionId: sessionId.value,
      sender: 'user',
      content: text,
      seq: 0,
      timestamp: Date.now(),
    }
    messages.value.push(optimistic)
    client.send(text)
  }

  function markSent(messageId: string, seq: number): void {
    if (!messageId) return
    const last = messages.value[messages.value.length - 1]
    if (last && last.id.startsWith('local-')) {
      last.id = messageId
      last.seq = seq
    }
  }

  /** 手动重连（用户点击）。 */
  function reconnect(): void {
    connect()
  }

  /**
   * 重置本地会话态：切换/新建会话前调用。
   * 先断开旧连接，避免旧会话的迟到片段（chunk/chat）混入新窗口。
   */
  function resetLocalState(): void {
    client?.disconnect()
    client = null
    messages.value = []
    rating.value = 0
    feedbackSubmitted.value = false
    feedbackError.value = ''
    error.value = ''
  }

  /**
   * 新建会话：申请新的 sessionId 并切到空窗口。
   * 旧会话在服务端保留（历史仍可查、仍可被坐席接管），此处只是前端切窗口。
   *
   * @returns 是否创建成功；失败时已写入 ``error`` 供页面提示
   */
  async function newSession(): Promise<boolean> {
    if (!currentUserId) {
      error.value = '登录态未就绪，暂无法新建会话'
      return false
    }
    resetLocalState()
    status.value = 'connecting'
    try {
      sessionId.value = await createSession(currentUserId)
      sessionStorage.setItem(SESSION_KEY, sessionId.value)
    } catch (e) {
      status.value = 'disconnected'
      error.value = (e as Error).message || '新建会话失败，请稍后重试'
      return false
    }
    connect()
    void loadSessions()
    return true
  }

  /**
   * 切换到指定历史会话：重置本地态 → 用该会话重连 → 回源拉取全部历史。
   *
   * 回源机制与刷新页面一致（`resumeFrom(0)`），故切过去的会话能看到已落库的全部消息；
   * 若该会话在服务端已不存在（例如服务端重启过且会话未持久化），则表现为空窗口，
   * 此时列表会在下次 `loadSessions()` 时自动把它剔除。
   */
  async function switchSession(targetId: string): Promise<void> {
    if (!targetId || targetId === sessionId.value) return
    resetLocalState()
    sessionId.value = targetId
    sessionStorage.setItem(SESSION_KEY, targetId)
    status.value = 'connecting'
    connect()
    client?.resumeFrom(0)
  }

  /**
   * 提交满意度评价（M5-5.1）：评分 1~5，意见可选。
   * 业务拒绝（ok=false，如会话不存在）与通道异常都只提示，不中断会话。
   */
  async function submitRating(comment = ''): Promise<void> {
    if (feedbackSubmitted.value || rating.value <= 0) return
    if (!sessionId.value) {
      feedbackError.value = '会话未就绪，暂无法评价'
      return
    }
    try {
      const resp = await submitFeedback(sessionId.value, rating.value, comment)
      if (!resp.ok) {
        feedbackError.value = resp.reason || '提交失败，请稍后重试'
        return
      }
      feedbackSubmitted.value = true
      feedbackError.value = ''
    } catch {
      feedbackError.value = '提交失败，请稍后重试'
    }
  }

  onUnmounted(() => client?.disconnect())

  return {
    messages,
    status,
    error,
    rating,
    feedbackSubmitted,
    feedbackError,
    sessions,
    sessionsError,
    activeSessionId: sessionId,
    init,
    send,
    reconnect,
    newSession,
    switchSession,
    loadSessions,
    submitRating,
  }
}
