<template>
  <!-- M5-5.1 运营报表页：会话漏斗 / Token 消耗 / 满意度（naive-ui + 内联 SVG，不引入图表库） -->
  <n-space vertical :size="16" style="padding: 16px; max-width: 1080px; margin: 0 auto">
    <!-- 顶部：租户 + 日期范围筛选 -->
    <n-card size="small" title="运营报表">
      <template #header-extra>
        <n-button size="small" text type="primary" @click="goPortal">返回用户端</n-button>
      </template>
      <n-space vertical :size="10">
        <n-space align="center" :size="12">
          <n-input v-model:value="tenantId" placeholder="租户" style="width: 180px" />
          <n-date-picker v-model:value="dateRange" type="daterange" clearable style="width: 320px" />
          <n-button type="primary" :loading="loading" @click="load">查询</n-button>
          <n-tag size="small" :bordered="false">未选日期时为最近 7 天</n-tag>
        </n-space>
        <n-alert v-if="error" type="warning" :show-icon="false" size="small">{{ error }}</n-alert>
      </n-space>
    </n-card>

    <!-- ① 会话漏斗：5 阶段会话数 + 相邻/累计转化率 -->
    <n-card size="small">
      <template #header>
        <n-space align="center" :size="8">
          <span>会话漏斗</span>
          <n-tag size="small" :bordered="false">建会话 {{ funnel.created_sessions }}</n-tag>
        </n-space>
      </template>
      <n-empty v-if="funnel.stages.length === 0" description="暂无数据" size="small" />
      <template v-else>
        <!-- 梯形漏斗：宽度按各阶段会话数占比绘制（纯内联 SVG，无第三方图表库） -->
        <svg class="funnel-svg" :viewBox="`0 0 100 ${funnelSvgHeight}`" preserveAspectRatio="none">
          <polygon
            v-for="shape in funnelShapes"
            :key="shape.stage"
            :points="shape.points"
            class="funnel-shape"
          />
        </svg>
        <n-space vertical :size="10">
          <div v-for="stage in funnel.stages" :key="stage.stage" class="funnel-row">
            <span class="funnel-label">{{ stage.label }}</span>
            <n-progress
              type="line"
              :percentage="percent(stage.total_rate)"
              :height="14"
              :border-radius="4"
              :show-indicator="false"
            />
            <span class="funnel-value">{{ stage.sessions }}</span>
            <span class="funnel-rate">
              相邻 {{ rate(stage.step_rate) }} · 累计 {{ rate(stage.total_rate) }}
            </span>
          </div>
        </n-space>
      </template>
    </n-card>

    <!-- ② Token 消耗：汇总 + 日 × 提供方 × 模型 明细 -->
    <n-card size="small" title="Token 消耗">
      <n-space :size="24" align="baseline">
        <n-statistic label="调用次数" :value="token.total_requests" />
        <n-statistic label="输入 token" :value="token.total_prompt_tokens" />
        <n-statistic label="输出 token" :value="token.total_completion_tokens" />
        <n-statistic label="估算占比" :value="estimatedRatio" suffix="%" />
      </n-space>
      <n-data-table
        :columns="tokenColumns"
        :data="token.rows"
        :bordered="false"
        :single-line="false"
        size="small"
        style="margin-top: 12px"
      />
    </n-card>

    <!-- ③ 满意度：CSAT / 平均分 / 评分分布 / 最近评价 -->
    <n-card size="small" title="满意度">
      <n-space :size="24" align="baseline">
        <n-statistic label="评价数" :value="csat.feedback_count" />
        <n-statistic label="平均分" :value="csat.average_rating.toFixed(2)" />
        <n-statistic label="CSAT（≥4 星占比）" :value="rate(csat.csat)" />
      </n-space>
      <div v-if="csat.distribution.length" class="csat-bars">
        <div v-for="bucket in csat.distribution" :key="bucket.rating" class="csat-row">
          <span class="csat-star">{{ bucket.rating }} 星</span>
          <div class="csat-bar-track">
            <div class="csat-bar" :style="{ width: barWidth(bucket.count) }"></div>
          </div>
          <span class="csat-count">{{ bucket.count }}</span>
        </div>
      </div>
      <n-empty v-else description="暂无评价" size="small" />
      <n-list v-if="csat.recent.length" bordered style="margin-top: 12px">
        <n-list-item v-for="item in csat.recent" :key="item.session_id">
          <n-space align="center" :size="8">
            <n-rate :value="item.rating" readonly size="small" />
            <span class="feedback-comment">{{ item.comment || '（未填写意见）' }}</span>
            <span class="feedback-time">{{ formatTime(item.created_at_ms) }}</span>
          </n-space>
        </n-list-item>
      </n-list>
    </n-card>
  </n-space>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  NAlert,
  NButton,
  NCard,
  NDataTable,
  NDatePicker,
  NEmpty,
  NInput,
  NList,
  NListItem,
  NProgress,
  NRate,
  NSpace,
  NStatistic,
  NTag,
  type DataTableColumns,
} from 'naive-ui'
import { useReport } from '../../composables/useReport'
import type { TokenUsageRow } from '../../api/report'

const router = useRouter()
const { tenantId, dateRange, loading, error, funnel, token, csat, load } = useReport()

// 每阶段高度（SVG 用户单位），梯形图总高随阶段数增长
const STAGE_HEIGHT = 9

onMounted(() => void load())

/** 会话数 → 比率分母（各阶段取最大值，保证图形宽度可比）。 */
const maxSessions = computed(() => {
  const counts = funnel.value.stages.map((stage) => stage.sessions)
  return Math.max(1, ...counts)
})

const funnelSvgHeight = computed(() => Math.max(STAGE_HEIGHT, funnel.value.stages.length * STAGE_HEIGHT))

/**
 * 梯形漏斗形状：每阶段上宽为「本阶段会话数」、下宽为「下一阶段会话数」，
 * 按最大值归一化后居中绘制，直观呈现逐级流失。
 */
const funnelShapes = computed(() =>
  funnel.value.stages.map((stage, index) => {
    const max = maxSessions.value
    const topWidth = (stage.sessions / max) * 80
    const nextSessions = funnel.value.stages[index + 1]?.sessions ?? stage.sessions
    const bottomWidth = (nextSessions / max) * 80
    const top = index * STAGE_HEIGHT
    const bottom = top + STAGE_HEIGHT
    const points = [
      [50 - topWidth / 2, top],
      [50 + topWidth / 2, top],
      [50 + bottomWidth / 2, bottom],
      [50 - bottomWidth / 2, bottom],
    ]
    return { stage: stage.stage, points: points.map((point) => point.join(',')).join(' ') }
  }),
)

/** 估算条数占比（%）：无法取得真实 usage 的调用比例，用于评估数据可信度。 */
const estimatedRatio = computed(() => {
  if (token.value.total_requests <= 0) return '0.0'
  return ((token.value.total_estimated_requests / token.value.total_requests) * 100).toFixed(1)
})

/** Token 明细列（日 × 提供方 × 模型）。 */
const tokenColumns: DataTableColumns<TokenUsageRow> = [
  { title: '统计日', key: 'stat_date', width: 110 },
  { title: '提供方', key: 'provider', width: 100 },
  { title: '模型', key: 'model' },
  { title: '调用次数', key: 'requests', width: 90 },
  { title: '输入 token', key: 'prompt_tokens', width: 110 },
  { title: '输出 token', key: 'completion_tokens', width: 110 },
  { title: '估算条数', key: 'estimated_requests', width: 90 },
  { title: '平均首字(ms)', key: 'first_token_ms_avg', width: 120 },
]

/** 比率 → 百分比数值（n-progress 需要 0~100）。 */
function percent(value: number): number {
  return Math.min(100, Math.max(0, value * 100))
}

/** 比率 → 百分比文案。 */
function rate(value: number): string {
  return `${percent(value).toFixed(1)}%`
}

/** 评分分布条宽度（相对最高星级占比，避免全宽误导）。 */
function barWidth(count: number): string {
  const max = Math.max(1, ...csat.value.distribution.map((bucket) => bucket.count))
  return `${(count / max) * 100}%`
}

/** epoch 毫秒 → 本地时间文案。 */
function formatTime(epochMs: number): string {
  if (!epochMs) return ''
  return new Date(epochMs).toLocaleString()
}

function goPortal(): void {
  void router.push('/')
}
</script>

<style scoped>
/* 梯形漏斗：等宽缩放以适配不同阶段高度 */
.funnel-svg {
  width: 100%;
  height: 140px;
  margin-bottom: 12px;
}

.funnel-shape {
  fill: #2080f0;
  opacity: 0.75;
  stroke: #fff;
  stroke-width: 0.4;
}

.funnel-row {
  display: grid;
  grid-template-columns: 72px 1fr 56px 190px;
  align-items: center;
  gap: 10px;
}

.funnel-label {
  font-size: 13px;
  color: #333;
}

.funnel-value {
  font-size: 13px;
  font-weight: 600;
  text-align: right;
}

.funnel-rate {
  font-size: 12px;
  color: #999;
}

.csat-bars {
  margin-top: 12px;
}

.csat-row {
  display: grid;
  grid-template-columns: 48px 1fr 48px;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}

.csat-star {
  font-size: 13px;
  color: #333;
}

.csat-bar-track {
  height: 12px;
  background: #f0f0f0;
  border-radius: 6px;
  overflow: hidden;
}

.csat-bar {
  height: 100%;
  background: #18a058;
  border-radius: 6px;
}

.csat-count {
  font-size: 13px;
  color: #666;
  text-align: right;
}

.feedback-comment {
  font-size: 13px;
  color: #333;
}

.feedback-time {
  font-size: 12px;
  color: #999;
}
</style>
