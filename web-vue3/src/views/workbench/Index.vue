<template>
  <!-- M2-C 客服工作台：队列接管 + 实时消息观察 + 快捷回复 + 一键建单 -->
  <div class="workbench">
    <!-- 左栏：待接管队列 / 我的会话 -->
    <aside class="pane queue-pane">
      <n-card size="small" title="待接管队列" :bordered="true" class="pane-card">
        <template #header-extra>
          <n-tag size="small" round>{{ queue.length }}</n-tag>
        </template>
        <n-empty v-if="queue.length === 0" description="暂无待接管会话" size="small" />
        <div
          v-for="s in queue"
          :key="s.session_id"
          class="session-item"
          :class="{ active: selected?.session_id === s.session_id }"
          @click="selectSession(s)"
        >
          <div class="session-title">{{ s.user_id }}</div>
          <div class="session-meta">{{ shortId(s.session_id) }} · {{ s.message_count }} 条</div>
        </div>
      </n-card>

      <n-card size="small" title="我的会话" :bordered="true" class="pane-card">
        <template #header-extra>
          <n-tag size="small" round type="success">{{ mySessions.length }}</n-tag>
        </template>
        <n-empty v-if="mySessions.length === 0" description="尚未接管会话" size="small" />
        <div
          v-for="s in mySessions"
          :key="s.session_id"
          class="session-item"
          :class="{ active: selected?.session_id === s.session_id }"
          @click="selectSession(s)"
        >
          <div class="session-title">{{ s.user_id }}</div>
          <div class="session-meta">{{ shortId(s.session_id) }} · {{ s.message_count }} 条</div>
        </div>
      </n-card>
    </aside>

    <!-- 中栏：消息区 + 回复 -->
    <main class="pane chat-pane">
      <n-card size="small" :bordered="true" class="pane-card">
        <template #header>
          <span>会话消息</span>
        </template>
        <template #header-extra>
          <n-space :size="8" align="center">
            <n-tag v-if="selected" size="small" :type="stateTagType(selected.state)">
              {{ stateLabel(selected.state) }}
            </n-tag>
            <n-tag size="small" :type="wsStatus === 'connected' ? 'success' : 'warning'" round>
              {{ wsStatus === 'connected' ? '已连接' : '连接中' }}
            </n-tag>
            <n-button
              v-if="selected && canTakeOver(selected.state)"
              size="tiny"
              type="primary"
              @click="takeOverSession(selected)"
            >
              接管
            </n-button>
          </n-space>
        </template>

        <n-empty v-if="!selected" description="从左侧选择一个会话" size="small" />
        <template v-else>
          <div ref="listEl" class="chat-list">
            <n-empty v-if="messages.length === 0" description="暂无消息" size="small" />
            <div
              v-for="msg in messages"
              :key="msg.id || msg.seq"
              class="chat-row"
              :class="msg.sender"
            >
              <div class="bubble">{{ msg.content }}</div>
              <div class="meta">{{ senderLabel(msg.sender) }} · {{ msg.seq || '…' }}</div>
            </div>
          </div>
          <n-space align="center" class="reply-bar">
            <n-input
              v-model:value="draft"
              placeholder="输入回复，回车发送"
              @keydown.enter.prevent="submitReply"
            />
            <n-button type="primary" :disabled="!draft.trim()" @click="submitReply">发送</n-button>
          </n-space>
        </template>
      </n-card>
    </main>

    <!-- 右栏：工单 -->
    <aside class="pane ticket-pane">
      <n-card size="small" title="工单" :bordered="true" class="pane-card">
        <template #header-extra>
          <n-tag size="small" round>{{ tickets.length }}</n-tag>
        </template>
        <n-empty v-if="!selected" description="选择会话后可建单" size="small" />
        <template v-else>
          <n-space vertical :size="8">
            <n-input v-model:value="ticketTitle" size="small" placeholder="工单标题" />
            <n-input
              v-model:value="ticketDesc"
              size="small"
              type="textarea"
              :rows="2"
              placeholder="问题描述"
            />
            <n-button size="small" type="primary" @click="submitTicket">一键建单</n-button>
          </n-space>

          <n-divider style="margin: 12px 0" />

          <n-empty v-if="tickets.length === 0" description="该会话暂无工单" size="small" />
          <div v-for="t in tickets" :key="t.ticket_id" class="ticket-item">
            <div class="ticket-head">
              <span class="ticket-title">{{ t.title }}</span>
              <n-tag size="tiny" :type="ticketTagType(t.status)">{{ t.status }}</n-tag>
            </div>
            <div class="ticket-meta">{{ t.description || '（无描述）' }}</div>
            <n-space :size="6" style="margin-top: 6px">
              <n-button
                v-if="t.status === 'CREATED'"
                size="tiny"
                @click="transitionTicket(t.ticket_id, 'PROCESSING')"
              >
                处理中
              </n-button>
              <n-button
                v-if="t.status !== 'CLOSED'"
                size="tiny"
                type="warning"
                @click="transitionTicket(t.ticket_id, 'CLOSED')"
              >
                关闭
              </n-button>
            </n-space>
          </div>
        </template>
      </n-card>
    </aside>
  </div>

  <n-alert
    v-if="error"
    class="error-bar"
    type="warning"
    :show-icon="false"
    closable
    @close="error = ''"
  >
    {{ error }}
  </n-alert>
</template>

<script setup lang="ts">
import { ref, watch, nextTick, onMounted } from 'vue'
import {
  NCard,
  NSpace,
  NInput,
  NButton,
  NTag,
  NAlert,
  NEmpty,
  NDivider,
} from 'naive-ui'
import { useWorkbench } from '../../composables/useWorkbench'
import type { TicketStatus } from '../../api/agent'

const {
  queue,
  mySessions,
  selected,
  messages,
  tickets,
  wsStatus,
  error,
  init,
  takeOverSession,
  selectSession,
  sendReply,
  createTicketForSession,
  transitionTicket,
} = useWorkbench()

const draft = ref('')
const ticketTitle = ref('')
const ticketDesc = ref('')
const listEl = ref<HTMLElement | null>(null)

onMounted(() => void init())

/** 会话状态展示文案。 */
function stateLabel(state: string): string {
  const map: Record<string, string> = {
    INIT: '待接管',
    AI_ACTIVE: 'AI应答中',
    HUMAN_CHAT: '人工中',
    CLOSED: '已关闭',
  }
  return map[state] ?? state
}

function stateTagType(state: string): 'default' | 'success' | 'warning' {
  if (state === 'HUMAN_CHAT') return 'success'
  if (state === 'CLOSED') return 'default'
  return 'warning'
}

/** 是否可人工接管：待接入（INIT）与 AI 应答中（AI_ACTIVE）均可（M3-3.11）。 */
function canTakeOver(state: string): boolean {
  return state === 'INIT' || state === 'AI_ACTIVE'
}

function ticketTagType(status: TicketStatus): 'info' | 'warning' | 'success' {
  if (status === 'CLOSED') return 'success'
  if (status === 'PROCESSING') return 'warning'
  return 'info'
}

/** 会话视角：user=客户（左），agent=客服（右）。 */
function senderLabel(sender: string): string {
  return sender === 'agent' ? '我' : '客户'
}

function shortId(id: string): string {
  return id.slice(0, 8)
}

async function submitReply(): Promise<void> {
  const text = draft.value.trim()
  if (!text) return
  draft.value = ''
  await sendReply(text)
  await nextTick()
  scrollToBottom()
}

async function submitTicket(): Promise<void> {
  if (!ticketTitle.value.trim()) return
  const ok = await createTicketForSession(ticketTitle.value, ticketDesc.value)
  if (ok) {
    ticketTitle.value = ''
    ticketDesc.value = ''
  }
}

watch(
  () => messages.value.length,
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
.workbench {
  display: flex;
  gap: 12px;
  padding: 12px;
  height: 100vh;
  box-sizing: border-box;
  background: #f5f6f8;
  /* 窄视口下保持三栏最小宽度并横向滚动，避免中栏被压塌、按钮被遮挡 */
  min-width: 900px;
}

.pane {
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow: hidden;
}

.queue-pane {
  width: 280px;
  flex: 0 0 280px;
}

.chat-pane {
  flex: 1 1 auto;
}

.ticket-pane {
  width: 320px;
  flex: 0 0 320px;
}

.pane-card {
  overflow: auto;
}

.session-item {
  padding: 8px;
  border-radius: 6px;
  cursor: pointer;
  border: 1px solid transparent;
}

.session-item:hover {
  background: #f0f4f8;
}

.session-item.active {
  background: #e8f1fb;
  border-color: #2b6cb0;
}

.session-title {
  font-size: 14px;
  font-weight: 500;
}

.session-meta {
  font-size: 12px;
  color: #999;
  margin-top: 2px;
}

.chat-list {
  height: calc(100vh - 240px);
  min-height: 260px;
  overflow-y: auto;
  padding: 8px;
  background: #fafafa;
  border-radius: 6px;
}

.chat-row {
  display: flex;
  flex-direction: column;
  margin-bottom: 10px;
}

.chat-row.user {
  align-items: flex-start;
}

.chat-row.agent {
  align-items: flex-end;
}

.bubble {
  max-width: 70%;
  padding: 8px 12px;
  border-radius: 8px;
  word-break: break-word;
  white-space: pre-wrap;
}

.chat-row.user .bubble {
  background: #fff;
  border: 1px solid #e0e0e0;
}

.chat-row.agent .bubble {
  background: #2b6cb0;
  color: #fff;
}

.meta {
  margin-top: 2px;
  font-size: 12px;
  color: #999;
}

.reply-bar {
  margin-top: 10px;
}

.ticket-item {
  padding: 8px;
  border: 1px solid #eee;
  border-radius: 6px;
  margin-bottom: 8px;
}

.ticket-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.ticket-title {
  font-size: 13px;
  font-weight: 500;
}

.ticket-meta {
  font-size: 12px;
  color: #888;
  margin-top: 4px;
}

.error-bar {
  position: fixed;
  bottom: 16px;
  left: 50%;
  transform: translateX(-50%);
  width: 420px;
}
</style>
