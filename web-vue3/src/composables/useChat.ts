import { ref, onUnmounted } from 'vue'
import { WssClient, createSession, type ChatMessage, type WsStatus } from '../api/wss'

const SESSION_KEY = 'lingxi_portal_session'

/**
 * 用户端会话状态管理：
 * - 会话 ID 持久化到 sessionStorage（刷新页面复用，避免重复建会话）；
 * - WSS 断连自动重连 + resume 续传；
 * - 本地乐观渲染用户消息，坐席消息由服务端 chat 信封驱动。
 */
export function useChat() {
  const messages = ref<ChatMessage[]>([])
  const status = ref<WsStatus>('disconnected')
  const error = ref('')

  let client: WssClient | null = null
  let currentUserId = ''
  let sessionId = sessionStorage.getItem(SESSION_KEY) ?? ''

  async function init(userId: string): Promise<void> {
    currentUserId = userId
    if (!sessionId) {
      sessionId = await createSession(userId)
      sessionStorage.setItem(SESSION_KEY, sessionId)
    }
    connect()
    // 刷新页面后从会话域回源补齐历史消息。
    client?.resumeFrom(0)
  }

  function connect(): void {
    client?.disconnect()
    client = new WssClient(sessionId, currentUserId, (ev) => {
      switch (ev.type) {
        case 'status':
          status.value = ev.status
          break
        case 'chat':
          mergeMessage(ev.message)
          break
        case 'chunk':
          applyChunk(ev.messageId, ev.delta, ev.finished, ev.error)
          break
        case 'ack':
          markSent(ev.messageId, ev.seq)
          break
        case 'error':
          error.value = ev.reason
          break
      }
    })
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
   */
  function applyChunk(messageId: string, delta: string, finished: boolean, chunkError: string): void {
    if (!messageId) return
    let target = messages.value.find((m) => m.id === messageId)
    if (!target && !finished) {
      target = {
        id: messageId,
        sessionId,
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
      if (finished) target.streaming = false
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
      sessionId,
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

  onUnmounted(() => client?.disconnect())

  return { messages, status, error, init, send, reconnect }
}
