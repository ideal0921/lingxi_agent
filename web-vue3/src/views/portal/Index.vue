<template>
  <!-- M2-A4 用户端会话页：WSS 实时收发、断连自动重连 + resume 续传 -->
  <n-card
    title="灵犀智能客服"
    style="max-width: 720px; margin: 24px auto"
    :bordered="true"
  >
    <template #header-extra>
      <n-tag :type="statusTagType" size="small" round>{{ statusText }}</n-tag>
    </template>

    <n-space vertical :size="12">
      <n-alert v-if="error" type="warning" :show-icon="false" closable @close="error = ''">
        {{ error }}
      </n-alert>

      <!-- 消息列表 -->
      <div ref="listEl" class="chat-list">
        <n-empty
          v-if="messages.length === 0"
          description="发送第一条消息，开始会话"
          size="small"
        />
        <div
          v-for="msg in messages"
          :key="msg.id || msg.seq"
          class="chat-row"
          :class="msg.sender"
        >
          <div class="bubble">
            {{ msg.content }}<span v-if="msg.streaming" class="caret">▍</span>
          </div>
          <div class="meta">{{ senderLabel(msg.sender) }} · {{ msg.seq || '…' }}</div>
          <!-- 人工接管中断提示（M4-4.5）：正常业务态，非错误 -->
          <n-alert
            v-if="msg.interrupted"
            class="takeover-hint"
            type="warning"
            :show-icon="false"
            size="small"
          >
            已转人工，正在为您安排坐席
          </n-alert>
        </div>
      </div>

      <!-- 输入区 -->
      <n-space align="center">
        <n-input
          v-model:value="draft"
          placeholder="输入消息，回车发送"
          :disabled="!connected"
          @keyup.enter="submit"
        />
        <n-button type="primary" :disabled="!connected || !draft.trim()" @click="submit">
          发送
        </n-button>
        <n-button v-if="!connected" size="small" @click="reconnect">重连</n-button>
      </n-space>

      <!-- 满意度评价（M5-5.1）：提交后置为已评价态，失败仅提示不影响会话 -->
      <n-divider style="margin: 4px 0" />
      <n-space vertical :size="8">
        <n-space align="center" :size="8">
          <span class="feedback-title">本次服务满意吗？</span>
          <n-rate v-model:value="rating" :count="5" :disabled="feedbackSubmitted" />
        </n-space>
        <template v-if="rating > 0 && !feedbackSubmitted">
          <n-input
            v-model:value="feedbackComment"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-count
            placeholder="补充意见（可选）"
          />
          <n-button type="primary" size="small" @click="submitRating(feedbackComment)">
            提交评价
          </n-button>
        </template>
        <n-alert v-if="feedbackError" type="warning" :show-icon="false" size="small">
          {{ feedbackError }}
        </n-alert>
        <n-alert v-if="feedbackSubmitted" type="success" :show-icon="false" size="small">
          感谢您的评价！
        </n-alert>
      </n-space>
    </n-space>
  </n-card>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted } from 'vue'
import {
  NCard,
  NSpace,
  NInput,
  NButton,
  NTag,
  NAlert,
  NEmpty,
  NDivider,
  NRate,
} from 'naive-ui'
import { useChat } from '../../composables/useChat'
import type { WsStatus } from '../../api/wss'

const {
  messages,
  status,
  error,
  rating,
  feedbackSubmitted,
  feedbackError,
  init,
  send,
  reconnect,
  submitRating,
} = useChat()

const draft = ref('')
const feedbackComment = ref('')
const listEl = ref<HTMLElement | null>(null)

// 用户标识：本地持久化，联调环境固定 demo 用户。
const USER_KEY = 'lingxi_user_id'
let userId = localStorage.getItem(USER_KEY) ?? 'demo-user'
if (!localStorage.getItem(USER_KEY)) {
  localStorage.setItem(USER_KEY, userId)
}

onMounted(() => {
  void init(userId)
})

const connected = computed(() => status.value === 'connected')

const statusText = computed(() => {
  const map: Record<WsStatus, string> = {
    connecting: '连接中…',
    connected: '在线',
    reconnecting: '重连中…',
    disconnected: '已断开',
  }
  return map[status.value] ?? '未知'
})

const statusTagType = computed(() => {
  const map: Record<WsStatus, 'success' | 'warning' | 'error' | 'default'> = {
    connecting: 'warning',
    connected: 'success',
    reconnecting: 'warning',
    disconnected: 'error',
  }
  return map[status.value] ?? 'default'
})

function senderLabel(sender: string): string {
  return sender === 'agent' ? '客服' : '我'
}

async function submit(): Promise<void> {
  const text = draft.value.trim()
  if (!text || !connected.value) return
  draft.value = ''
  send(text)
  await nextTick()
  scrollToBottom()
}

// 消息数量或流式内容增长时滚动到底部（打字机过程中内容变化不改变数量）。
watch(
  () => [messages.value.length, messages.value.reduce((total, msg) => total + msg.content.length, 0)],
  async () => {
    await nextTick()
    scrollToBottom()
  },
)

function scrollToBottom(): void {
  if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight
}
</script>

<style scoped>
.chat-list {
  height: 420px;
  overflow-y: auto;
  border: 1px solid #eee;
  border-radius: 6px;
  padding: 12px;
  background: #fafafa;
}

.chat-row {
  display: flex;
  flex-direction: column;
  margin-bottom: 10px;
}

.chat-row.user {
  align-items: flex-end;
}

.chat-row.agent {
  align-items: flex-start;
}

.bubble {
  max-width: 70%;
  padding: 8px 12px;
  border-radius: 8px;
  word-break: break-word;
  white-space: pre-wrap;
}

.chat-row.user .bubble {
  background: #2080f0;
  color: #fff;
}

.chat-row.agent .bubble {
  background: #fff;
  border: 1px solid #e0e0e0;
}

.meta {
  margin-top: 2px;
  font-size: 12px;
  color: #999;
}

/* 评价区标题（M5-5.1） */
.feedback-title {
  font-size: 13px;
  color: #666;
}

/* 人工接管中断提示：与消息同宽上限，复用 naive-ui 警告样式 */
.takeover-hint {
  margin-top: 4px;
  max-width: 70%;
}

/* 流式打字机光标：仅在 streaming 状态下闪烁，提示回答仍在生成 */
.caret {
  margin-left: 1px;
  animation: caret-blink 1s step-start infinite;
}

@keyframes caret-blink {
  50% {
    opacity: 0;
  }
}
</style>
