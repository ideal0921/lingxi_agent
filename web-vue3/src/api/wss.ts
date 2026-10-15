import http from './http'

/**
 * WSS 消息信封：与网关协议（gateway-go/internal/ws/message.go）保持一致。
 */
export interface ChatEnvelope {
  type: 'ping' | 'pong' | 'send' | 'ack' | 'chat' | 'chunk' | 'error' | 'resume' | 'suggest'
  session_id?: string
  sender?: string
  content?: string
  seq?: number
  last_seq?: number
  message_id?: string
  /** 请求标识（suggest 专用，M4-4.6）：用于同步/异步结果去重 */
  request_id?: string
  /** 回复建议候选（suggest 专用）：仅坐席端可见 */
  suggestions?: string[]
  index?: number
  finished?: boolean
  /** 本轮是否因人工接管而中断（chunk 专用，M4-4.5） */
  interrupted?: boolean
  /** 中断原因：agent_takeover / handoff_requested */
  reason?: string
  timestamp_ms?: number
  error?: string
}

/** 会话消息（页面渲染模型）。 */
export interface ChatMessage {
  id: string
  sessionId: string
  sender: 'user' | 'agent' | 'system'
  content: string
  seq: number
  timestamp: number
  /** 是否仍在流式生成中（打字机态，M3-3.8） */
  streaming?: boolean
  /** 本轮回答是否因人工接管被中断（M4-4.5） */
  interrupted?: boolean
}

/** 连接状态回调事件。 */
export type WssEvent =
  | { type: 'chat'; message: ChatMessage }
  | { type: 'chunk'; messageId: string; delta: string; index: number; finished: boolean; error: string; interrupted: boolean; reason: string }
  | { type: 'ack'; seq: number; messageId: string }
  | { type: 'suggest'; sessionId: string; requestId: string; suggestions: string[] }
  | { type: 'error'; reason: string }
  | { type: 'status'; status: WsStatus }

export type WsStatus = 'connecting' | 'connected' | 'reconnecting' | 'disconnected'

/** WSS 连接角色：坐席观察端用于接收「仅推坐席」的下行（如回复建议）。 */
export type WssRole = 'user' | 'agent'

/**
 * 创建会话：POST /api/v1/session（网关转发 Java 会话域）。
 * @param userId 用户标识
 * @returns sessionId
 */
export async function createSession(userId: string): Promise<string> {
  const resp = (await http.post('/session', { user_id: userId, channel: 'web' })) as {
    session_id: string
  }
  return resp.session_id
}

/** 评价提交结果：ok=false 时 reason 为拒绝原因（会话不存在/评分越界等）。 */
export interface FeedbackResult {
  ok: boolean
  reason: string
}

/**
 * 提交会话满意度评价（M5-5.1，走 HTTP 而非 WS）。
 * 租户/用户由服务端按会话 ID 解析，故前端不传 tenant_id（防跨租户伪造）。
 */
export async function submitFeedback(
  sessionId: string,
  rating: number,
  comment = '',
  tags = '',
): Promise<FeedbackResult> {
  return (await http.post(`/session/${sessionId}/feedback`, {
    rating,
    comment,
    tags,
  })) as FeedbackResult
}

/**
 * 会话长连接客户端：
 * - 心跳保活由浏览器与服务端 ping/pong 帧自动完成；
 * - 断连后按指数退避自动重连，重连成功发送 resume(last_seq) 补齐遗漏消息；
 * - last_seq 游标随 ack/chat 消息实时推进。
 */
export class WssClient {
  private socket: WebSocket | null = null
  private lastSeq = 0
  private retryCount = 0
  private reconnectTimer: number | null = null
  private closedByUser = false
  private resumeOnConnect = false

  constructor(
    private readonly sessionId: string,
    private readonly userId: string,
    private readonly onEvent: (ev: WssEvent) => void,
    /** 连接角色：坐席观察端传 'agent'（缺省 user，不追加 role 查询参数）。 */
    private readonly role: WssRole = 'user',
  ) {}

  /** 建立连接；断连后自动重连。 */
  connect(): void {
    this.closedByUser = false
    this.emitStatus('connecting')
    const protocol = location.protocol === 'https:' ? 'wss' : 'ws'
    // 坐席端追加 role=agent，网关据此仅向其推送「仅推坐席」下行（如回复建议）。
    const roleQuery = this.role === 'agent' ? '&role=agent' : ''
    const url = `${protocol}://${location.host}/ws?session_id=${this.sessionId}${roleQuery}`
    const socket = new WebSocket(url)
    this.socket = socket

    socket.onopen = () => {
      this.retryCount = 0
      this.emitStatus('connected')
      // 断连重连 / 刷新拉历史：携带游标续传。
      if (this.resumeOnConnect || this.lastSeq > 0) {
        this.resumeOnConnect = false
        this.sendEnvelope({ type: 'resume', last_seq: this.lastSeq })
      }
    }

    socket.onmessage = (event) => this.handleMessage(event.data)

    socket.onclose = () => {
      this.socket = null
      if (this.closedByUser) {
        this.emitStatus('disconnected')
        return
      }
      this.scheduleReconnect()
    }

    socket.onerror = () => {
      socket.close()
    }
  }

  /** 发送用户消息。 */
  send(content: string): void {
    this.sendEnvelope({ type: 'send', sender: this.userId, content })
  }

  /** 主动断开（用户离开页面）。 */
  disconnect(): void {
    this.closedByUser = true
    if (this.reconnectTimer !== null) {
      window.clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.socket?.close()
  }

  /** 请求回源续传：下次连接建立后发送 resume(last_seq)，用于刷新页面拉取历史。 */
  resumeFrom(lastSeq: number): void {
    this.lastSeq = lastSeq
    this.resumeOnConnect = true
  }

  /** 当前已连接。 */
  get connected(): boolean {
    return this.socket?.readyState === WebSocket.OPEN
  }

  private handleMessage(raw: string): void {
    let env: ChatEnvelope
    try {
      env = JSON.parse(raw) as ChatEnvelope
    } catch {
      this.onEvent({ type: 'error', reason: '非法消息格式' })
      return
    }

    switch (env.type) {
      case 'ack':
        if (env.seq) this.lastSeq = Math.max(this.lastSeq, env.seq)
        this.onEvent({ type: 'ack', seq: env.seq ?? 0, messageId: env.message_id ?? '' })
        break
      case 'chat':
        if (env.seq) this.lastSeq = Math.max(this.lastSeq, env.seq)
        this.onEvent({
          type: 'chat',
          message: {
            id: env.message_id ?? '',
            sessionId: env.session_id ?? this.sessionId,
            sender: env.sender === 'agent' ? 'agent' : 'user',
            content: env.content ?? '',
            seq: env.seq ?? 0,
            timestamp: env.timestamp_ms ?? Date.now(),
          },
        })
        break
      case 'chunk':
        // 流式片段不回退/推进会话游标：内容尚未落库，seq 由后续 chat 消息权威下发
        this.onEvent({
          type: 'chunk',
          messageId: env.message_id ?? '',
          delta: env.content ?? '',
          index: env.index ?? 0,
          finished: env.finished === true,
          error: env.error ?? '',
          interrupted: env.interrupted === true,
          reason: env.reason ?? '',
        })
        break
      case 'suggest':
        // 过载降级后 Java→Kafka→网关异步推送的候选建议（仅坐席端可见）。
        this.onEvent({
          type: 'suggest',
          sessionId: env.session_id ?? this.sessionId,
          requestId: env.request_id ?? '',
          suggestions: env.suggestions ?? [],
        })
        break
      case 'error':
        this.onEvent({ type: 'error', reason: env.error ?? '未知错误' })
        break
      default:
        // ping/pong 等心跳类信封无需业务处理。
        break
    }
  }

  /** 指数退避重连：1s → 2s → 4s → … 上限 15s。 */
  private scheduleReconnect(): void {
    const delay = Math.min(1000 * 2 ** this.retryCount, 15_000)
    this.retryCount += 1
    this.emitStatus('reconnecting')
    this.reconnectTimer = window.setTimeout(() => {
      this.connect()
    }, delay)
  }

  private sendEnvelope(env: Partial<ChatEnvelope> & { type: string }): void {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
      this.onEvent({ type: 'error', reason: '连接未就绪' })
      return
    }
    this.socket.send(JSON.stringify(env))
  }

  private emitStatus(status: WsStatus): void {
    this.onEvent({ type: 'status', status })
  }
}
