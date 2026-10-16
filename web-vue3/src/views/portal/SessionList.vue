<template>
  <!--
    历史会话列表：左列（≥1024）与窄屏抽屉复用同一份组件与逻辑。
    数据来自服务端（GET /api/v1/sessions），按最近活跃倒序。
  -->
  <div class="session-list">
    <div class="rail-head">
      <span class="rail-title">会话</span>
      <n-button size="tiny" secondary type="primary" :loading="creating" @click="emit('create')">
        新建
      </n-button>
    </div>

    <n-alert v-if="error" type="warning" :show-icon="false" size="small" class="rail-alert">
      {{ error }}
    </n-alert>

    <div class="rail-body">
      <n-empty v-if="sessions.length === 0" description="暂无历史会话" size="small" />
      <button
        v-for="item in sessions"
        :key="item.session_id"
        type="button"
        class="session-item"
        :class="{ 'is-active': item.session_id === activeId }"
        :title="item.last_preview"
        @click="emit('select', item.session_id)"
      >
        <span class="item-top">
          <span class="item-preview">{{ item.last_preview || '（空会话）' }}</span>
          <n-tag size="tiny" :bordered="false" :type="stateTagType(item.state)">
            {{ stateLabel(item.state) }}
          </n-tag>
        </span>
        <span class="item-meta">
          <span>{{ formatTime(item.last_active_at_ms) }}</span>
          <span>{{ item.message_count }} 条</span>
        </span>
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { NAlert, NButton, NEmpty, NTag } from 'naive-ui'
import type { SessionListItem } from '../../api/wss'

defineProps<{
  /** 历史会话（服务端返回，按最近活跃倒序） */
  sessions: SessionListItem[]
  /** 当前会话 ID（高亮当前项） */
  activeId: string
  /** 新建会话进行中（按钮 loading） */
  creating?: boolean
  /** 列表加载失败提示 */
  error?: string
}>()

const emit = defineEmits<{
  (e: 'select', sessionId: string): void
  (e: 'create'): void
}>()

/** 会话状态展示文案（与客服工作台保持一致，未知状态回退原值）。 */
function stateLabel(state: string): string {
  const map: Record<string, string> = {
    INIT: '待接管',
    AI_ACTIVE: 'AI应答中',
    WAITING_HUMAN: '待人工接入',
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

/** 相对时间文案：仅渲染时计算（列表不做秒级刷新，避免无谓定时器）。 */
function formatTime(epochMs: number): string {
  if (!epochMs) return '—'
  const diff = Date.now() - epochMs
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  return new Date(epochMs).toLocaleDateString()
}
</script>

<style scoped>
/* 高度撑满父容器（左列或抽屉体），内部列表区独立滚动 */
.session-list {
  display: flex;
  flex-direction: column;
  /* 同 .session-rail：作为横向 flex 项必须归零最小宽度，跨条目超长预览才被省略号截断 */
  min-width: 0;
  min-height: 0;
  height: 100%;
  /* 宽度撑满父容器：否则「无历史会话」时内容（空状态）撑不开，卡片会塌陷得比有会话时窄 */
  width: 100%;
  background: #fff;
  border: 1px solid #eef1f5;
  border-radius: 8px;
  overflow: hidden;
}

.rail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 12px;
  border-bottom: 1px solid #eef1f5;
}

.rail-title {
  font-size: 14px;
  font-weight: 600;
  color: #1f2733;
}

.rail-alert {
  margin: 8px 8px 0;
}

.rail-body {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

/* 会话项：原生 button 保证可聚焦与键盘可达（不用 div 模拟） */
.session-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
  padding: 8px 10px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: #fafbfc;
  text-align: left;
  cursor: pointer;
  font: inherit;
  transition:
    background 0.15s,
    border-color 0.15s;
}

.session-item:hover {
  background: #f2f6fc;
}

.session-item.is-active {
  background: #eef4ff;
  border-color: #4f9dff;
}

.item-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  min-width: 0;
}

.item-preview {
  flex: 1 1 auto;
  min-width: 0;
  font-size: 13px;
  color: #1f2733;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.item-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 11px;
  color: #98a2b3;
}
</style>
