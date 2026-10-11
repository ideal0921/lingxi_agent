// SSE 流式工具：基于 fetch + ReadableStream 逐 chunk 解析增量文本。
// M3 起用于 AI 回答的打字机渲染；断连续传由后端 seq 机制支持。

export interface StreamOptions {
  onChunk: (text: string) => void
  onDone?: () => void
  signal?: AbortSignal
}

export async function streamChat(
  url: string,
  body: unknown,
  { onChunk, onDone, signal }: StreamOptions,
): Promise<void> {
  const resp = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal,
  })
  if (!resp.ok) {
    throw new Error(`请求失败: ${resp.status}`)
  }
  if (!resp.body) {
    throw new Error('响应不支持流式读取')
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      onChunk(decoder.decode(value, { stream: true }))
    }
  } finally {
    reader.releaseLock()
  }
  onDone?.()
}
