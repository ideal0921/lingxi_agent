<template>
  <!-- 用户端会话页：两列（左侧历史会话列表 + 右侧会话窗口）；WSS 实时收发 + 断连重连/续传 -->
  <div class="portal-page">
    <!-- 左列：历史会话列表（≥1024 常显；窄屏收进抽屉，避免挤压会话区） -->
    <aside v-if="!compact" class="session-rail">
      <SessionList
        :sessions="sessions"
        :active-id="activeSessionId"
        :creating="creating"
        :error="sessionsError"
        @select="onSwitchSession"
        @create="onNewSession"
      />
    </aside>

    <n-card title="智能平台 · 在线会话" class="portal-card" :bordered="true">
      <template #header-extra>
        <n-space align="center" :size="8">
          <!-- 窄屏入口：左列收起后由抽屉承载同一份会话列表 -->
          <n-button v-if="compact" quaternary size="small" @click="railVisible = true">
            会话列表
          </n-button>
          <!-- 新建会话：申请新 sessionId 并切到空窗口 -->
          <n-button size="small" :loading="creating" @click="onNewSession">新建会话</n-button>
          <n-tag :type="statusTagType" size="small" round>{{ statusText }}</n-tag>
        </n-space>
      </template>

      <!-- 用普通 div 而非 n-space：n-space 会把子项包进无 class 的包装 div，
           导致 .chat-list 的 flex:1 作用不到（消息区无法吃掉剩余高度），故自行做 flex 列 -->
      <div class="portal-body">
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

        <!-- 输入区：自定义 flex 行（不用 n-space：其包装层会使输入框无法撑满，右边缘与消息框不对齐） -->
        <div class="reply-bar">
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
        </div>

        <!-- 满意度评价（M5-5.1）：提交后置为已评价态，失败仅提示不影响会话 -->
        <n-divider style="margin: 4px 0" />
        <n-space vertical :size="8">
          <n-space align="center" :size="8" :wrap="true">
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
      </div>
    </n-card>

    <!-- 窄屏抽屉：与左列复用同一个会话列表组件 -->
    <n-drawer
      v-model:show="railVisible"
      placement="left"
      :width="272"
      :content-style="railDrawerStyle"
    >
      <SessionList
        :sessions="sessions"
        :active-id="activeSessionId"
        :creating="creating"
        :error="sessionsError"
        @select="onSwitchSession"
        @create="onNewSession"
      />
    </n-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import {
  NCard,
  NDrawer,
  NSpace,
  NInput,
  NButton,
  NTag,
  NAlert,
  NEmpty,
  NDivider,
  NRate,
} from 'naive-ui'
import SessionList from './SessionList.vue'
import { useChat } from '../../composables/useChat'
import { useAuthStore } from '../../stores/auth'
import type { WsStatus } from '../../api/wss'

const {
  messages,
  status,
  error,
  rating,
  feedbackSubmitted,
  feedbackError,
  sessions,
  sessionsError,
  activeSessionId,
  init,
  send,
  reconnect,
  newSession,
  switchSession,
  submitRating,
} = useChat()

const draft = ref('')
const feedbackComment = ref('')
const listEl = ref<HTMLElement | null>(null)
/** 新建会话进行中（防重复点击）。 */
const creating = ref(false)

/** 窄屏（<1024，与 AdminLayout 同断点）收起左列，改用抽屉承载会话列表。 */
const compact = ref(false)
/** 窄屏抽屉显隐。 */
const railVisible = ref(false)
/** 抽屉内容零内边距：列表组件自带外框，避免双层留白。 */
const railDrawerStyle = { padding: '0' }

let mqCompact: MediaQueryList | null = null

/** 同步断点状态；回到宽屏时关闭抽屉，避免残留遮罩。 */
function syncCompact(): void {
  compact.value = mqCompact?.matches ?? false
  if (!compact.value) railVisible.value = false
}

// 用户标识取登录态用户名，作为会话 userId（不再写死 demo 用户）
const auth = useAuthStore()
const userId = auth.user?.username ?? ''

onMounted(() => {
  mqCompact = window.matchMedia('(max-width: 1023px)')
  syncCompact()
  mqCompact.addEventListener('change', syncCompact)
  void init(userId)
})

onUnmounted(() => {
  mqCompact?.removeEventListener('change', syncCompact)
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

/**
 * 新建会话：清空输入草稿与评价草稿，切到新的空会话窗口。
 * 失败由 useChat 写入 error，页面顶部横幅提示，不影响当前窗口继续使用。
 */
async function onNewSession(): Promise<void> {
  if (creating.value) return
  creating.value = true
  try {
    draft.value = ''
    feedbackComment.value = ''
    if (await newSession()) {
      await nextTick()
      scrollToBottom()
    }
  } finally {
    creating.value = false
  }
}

/**
 * 切换到历史会话：切过去后由既有 watch（消息数量变化）兜底滚动到底部，
 * 因为历史消息是经 WSS `chat` 信封异步回拉的。
 */
async function onSwitchSession(targetId: string): Promise<void> {
  railVisible.value = false
  await switchSession(targetId)
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
/*
 * 页面铺满内容区剩余宽高（剩余高度由 AdminLayout 的 .content-inner.is-fill-height
 * 提供：min-height: 100vh - 顶栏 56px）：整页为横向 flex（左列会话列表 + 右侧会话窗口），
 * 会话卡再纵向 flex 把高度逐级传给消息列表。
 * 原实现是 max-width:720px 居中 + 固定 clamp 高度，宽屏下会留下大片留白。
 */
.portal-page {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  gap: 12px;
}

.session-rail {
  flex: 0 0 248px;
  /* 关键：flex 项默认 min-width:auto = min-content，会话预览是 nowrap，
     超长预览会把左列撑开（曾把会话卡挤到 40px 宽），故必须显式归零 */
  min-width: 0;
  min-height: 0;
  display: flex;
}

.portal-card {
  flex: 1 1 auto;
  /* 关键：不加 min-width 时，超长消息气泡会把 flex 行撑破 */
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

/* 卡片内容区也转为纵向 flex，剩余高度才能继续传给消息列表。
   注意：naive-ui 2.40 的内部类名是 `n-card-content`（不带 `__`），写成 `n-card__content` 不会命中——
   曾因此让消息区只有 min-height 高度（实测用 CDP 取出真实类名才定位到）。 */
.portal-card :deep(.n-card-content) {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

/* 页面主体：自定义 flex 列（等价原 n-space vertical + size=12），撑满内容区 */
.portal-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
  flex: 1 1 auto;
  min-height: 0;
}

/* 除消息列表外的兄弟节点保持自身高度，不参与伸缩（避免输入区/评价区被压扁） */
.portal-body > *:not(.chat-list) {
  flex: 0 0 auto;
}

.chat-list {
  /* 吃掉剩余高度；内容为空时也不塌陷（min-height 兜底），超出则内部滚动 */
  flex: 1 1 auto;
  min-height: 160px;
  overflow-y: auto;
  border: 1px solid #eee;
  border-radius: 6px;
  padding: 12px;
  background: #fafafa;
}

/* 输入区与消息框左右对齐：输入框吃掉剩余宽度，发送/重连按钮保持自身宽度贴右 */
.reply-bar {
  display: flex;
  align-items: center;
  gap: 8px;
}

.reply-bar :deep(.n-input) {
  flex: 1 1 auto;
  min-width: 0;
}

.reply-bar :deep(.n-button) {
  flex: 0 0 auto;
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
