import http from './http'

/**
 * WSS 消息信封：与网关协议（gateway-go/internal/ws/message.go）保持一致。
 */
export interface ChatEnvelope {
  type: 'ping' | 'pong' | 'send' | 'ack' | 'chat' | 'chunk' | 'error' | 'resume'
  session_id?: string
  sender?: string
  content?: string
  seq?: number
  last_seq?: number
  message_id?: string
  index?: number
  finished?: boolean
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
}

/** 连接状态回调事件。 */
export type WssEvent =
  | { type: 'chat'; message: ChatMessage }
  | { type: 'chunk'; messageId: string; delta: string; index: number; finished: boolean; error: string }
  | { type: 'ack'; seq: number; messageId: string }
  | { type: 'error'; reason: string }
  | { type: 'status'; status: WsStatus }

export type WsStatus = 'connecting' | 'connected' | 'reconnecting' | 'disconnected'

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
  ) {}

  /** 建立连接；断连后自动重连。 */
  connect(): void {
    this.closedByUser = false
    this.emitStatus('connecting')
    const protocol = location.protocol === 'https:' ? 'wss' : 'ws'
    const url = `${protocol}://${location.host}/ws?session_id=${this.sessionId}`
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
