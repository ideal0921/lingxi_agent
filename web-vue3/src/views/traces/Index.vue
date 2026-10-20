<template>
  <!-- 接口链路追踪页（M5-5.9）：多条件筛选 + 远程分页表格 + 抽屉链路时间线 -->
  <div class="traces-page">
    <n-card size="small" title="接口链路追踪">
      <template #header-extra>
        <n-button size="small" type="primary" :loading="loading" @click="onSearch">刷新</n-button>
      </template>
      <n-space align="center" :size="12" :wrap="true">
        <n-input
          v-model:value="filter.traceId"
          placeholder="Trace ID"
          clearable
          class="filter-trace"
        />
        <n-input
          v-model:value="filter.entry"
          placeholder="入口（路径 / 方法 / topic）"
          clearable
          class="filter-entry"
        />
        <n-select
          v-model:value="filter.status"
          :options="statusOptions"
          placeholder="状态"
          style="width: 130px"
        />
        <n-date-picker v-model:value="dateRange" type="daterange" clearable class="filter-range" />
        <n-button type="primary" :loading="loading" @click="onSearch">查询</n-button>
      </n-space>
    </n-card>

    <n-card size="small">
      <n-data-table
        remote
        :columns="columns"
        :data="rows"
        :loading="loading"
        :pagination="pagination"
        :row-props="rowProps"
        :bordered="false"
        :single-line="false"
        :scroll-x="1530"
        size="small"
      />
    </n-card>

    <!-- 链路详情抽屉：按 startedAtMs 升序渲染时间线 -->
    <n-drawer v-model:show="detailVisible" placement="right" width="min(840px, 92vw)">
      <n-drawer-content :title="`链路详情 · ${detailTraceId}`" closable>
        <!-- 给最小高度：详情为空且加载中时，若无高度 spinner 不可见 -->
        <n-spin :show="detailLoading" class="trace-detail-spin">
          <template v-if="detailSpans.length > 0">
            <!-- 汇总：span 数与整条链路耗时（口径见 totalElapsedMs 注释） -->
            <n-space align="center" :size="10" :wrap="true" class="trace-summary">
              <n-tag size="small" :bordered="false">Span {{ detailSpans.length }}</n-tag>
              <n-tag size="small" :bordered="false" type="info">
                链路耗时 {{ totalElapsedMs }} ms
              </n-tag>
              <n-tag
                v-if="errorSpanCount > 0"
                size="small"
                :bordered="false"
                type="error"
              >
                失败 {{ errorSpanCount }} 段
              </n-tag>
            </n-space>

            <n-timeline>
              <n-timeline-item
                v-for="span in detailSpans"
                :key="span.spanId"
                :type="span.status === 'ERROR' ? 'error' : 'success'"
                :time="formatTime(span.startedAtMs)"
              >
                <div class="span-line">
                  <span class="span-title">{{ span.service }} · {{ span.component }} · {{ span.entry }}</span>
                  <n-tag
                    size="tiny"
                    :bordered="false"
                    :type="componentTagType(span.component)"
                  >
                    {{ span.component }}
                  </n-tag>
                  <n-tag
                    size="tiny"
                    :bordered="false"
                    :type="span.status === 'ERROR' ? 'error' : 'success'"
                  >
                    {{ span.status }}
                  </n-tag>
                  <span class="span-meta">
                    {{ span.elapsedMs }} ms
                    <template v-if="span.statusCode"> · code {{ span.statusCode }}</template>
                    <template v-if="span.httpMethod"> · {{ span.httpMethod }}</template>
                  </span>
                </div>
                <!-- 错误摘要（不含请求体，仅后端截断后的 errorMsg） -->
                <div v-if="span.errorMsg" class="span-error">{{ span.errorMsg }}</div>
              </n-timeline-item>
            </n-timeline>
          </template>
          <n-empty v-else-if="!detailLoading" description="暂无链路数据" size="small" />
        </n-spin>
      </n-drawer-content>
    </n-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, reactive, ref, type HTMLAttributes } from 'vue'
import {
  NCard,
  NDataTable,
  NDatePicker,
  NDrawer,
  NDrawerContent,
  NEllipsis,
  NEmpty,
  NInput,
  NSelect,
  NSpace,
  NSpin,
  NTag,
  NTimeline,
  NTimelineItem,
  useMessage,
  type DataTableColumns,
  type PaginationProps,
  type SelectOption,
} from 'naive-ui'
import { getTrace, listTraces, type ApiTraceSpanView } from '../../api/traces'

const message = useMessage()

const statusOptions: SelectOption[] = [
  { label: '全部', value: '' },
  { label: 'OK', value: 'OK' },
  { label: 'ERROR', value: 'ERROR' },
]

const filter = reactive({ traceId: '', entry: '', status: '' })
const dateRange = ref<[number, number] | null>(null)
const page = ref(1)
const size = ref(20)
const total = ref(0)
const rows = ref<ApiTraceSpanView[]>([])
const loading = ref(false)

// 详情抽屉状态
const detailVisible = ref(false)
const detailTraceId = ref('')
const detailSpans = ref<ApiTraceSpanView[]>([])
const detailLoading = ref(false)

const pagination = computed<PaginationProps>(() => ({
  page: page.value,
  pageSize: size.value,
  itemCount: total.value,
  showSizePicker: true,
  pageSizes: [20, 50, 100],
  prefix: ({ itemCount }) => `共 ${itemCount} 条`,
  onChange: (p: number) => {
    page.value = p
    void load()
  },
  onUpdatePageSize: (s: number) => {
    size.value = s
    page.value = 1
    void load()
  },
}))

/**
 * 链路耗时口径：取「最大完成时刻 − 最小开始时刻」的墙钟窗口，
 * 即 max(startedAtMs + elapsedMs) − min(startedAtMs)。
 * 之所以不用累加：Kafka 等异步 hop 与并发调用存在重叠，
 * 累加会重复计算同一段时间，墙钟窗口更贴近真实端到端耗时。
 */
const totalElapsedMs = computed(() => {
  if (detailSpans.value.length === 0) return 0
  const starts = detailSpans.value.map((span) => span.startedAtMs)
  const finishes = detailSpans.value.map((span) => span.startedAtMs + span.elapsedMs)
  return Math.max(...finishes) - Math.min(...starts)
})

const errorSpanCount = computed(
  () => detailSpans.value.filter((span) => span.status === 'ERROR').length,
)

/** 调用类型 → 标签配色（HTTP/GRPC/KAFKA/WSS 各一色便于快速识别）。 */
function componentTagType(component: string): 'default' | 'primary' | 'info' | 'success' | 'warning' {
  const map: Record<string, 'primary' | 'info' | 'success' | 'warning'> = {
    HTTP: 'info',
    GRPC: 'primary',
    KAFKA: 'warning',
    WSS: 'success',
  }
  return map[component] ?? 'default'
}

const columns: DataTableColumns<ApiTraceSpanView> = [
  { title: '时间', key: 'startedAtMs', width: 170, render: (row) => formatTime(row.startedAtMs) },
  {
    title: 'Trace ID',
    key: 'traceId',
    width: 210,
    // 链路 ID 为 32 位十六进制串，超出列宽会撑破单元格。
    // 为什么不用「按钮 + 内层省略」：按钮是行内块、宽度由内容撑开，单元格约束不到它，内层省略拿到的仍是超宽，
    // 故直接把 NEllipsis 作为单元格根元素——它是块级、宽度即单元格内容宽，超长自动省略；
    // 完整值用**原生 title** 悬浮提示给出（表格处于横向滚动容器内，原生提示最稳；NEllipsis 自带 tooltip 实测未弹出）。
    // 点击语义与行点击一致（打开链路详情），视觉上用「悬浮下划线 + 手型光标」保留可点击暗示。
    render: (row) =>
      h(
        NEllipsis,
        {
          title: row.traceId,
          class: 'trace-id-link',
          onClick: (e: MouseEvent) => {
            // 阻止冒泡，避免与行点击重复触发（两者都打开详情，但语义上只应有一次）
            e.stopPropagation()
            void openDetail(row.traceId)
          },
        },
        { default: () => row.traceId },
      ),
  },
  { title: '服务', key: 'service', width: 110 },
  {
    title: '类型',
    key: 'component',
    width: 100,
    render: (row) =>
      h(
        NTag,
        { size: 'small', bordered: false, type: componentTagType(row.component) },
        { default: () => row.component || '—' },
      ),
  },
  { title: '入口', key: 'entry', width: 260, ellipsis: { tooltip: true } },
  { title: '方法', key: 'httpMethod', width: 90, render: (row) => row.httpMethod || '—' },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: (row) =>
      h(
        NTag,
        { size: 'small', bordered: false, type: row.status === 'ERROR' ? 'error' : 'success' },
        { default: () => row.status || '—' },
      ),
  },
  { title: '状态码', key: 'statusCode', width: 90, render: (row) => row.statusCode || '—' },
  { title: '耗时(ms)', key: 'elapsedMs', width: 100 },
  {
    title: '主体',
    key: 'userId',
    width: 160,
    // 主体 ID 为 UUID（36 字符）且可能拼角色，同样需要截断 + 悬浮看全值
    ellipsis: { tooltip: true },
    render: (row) => (row.role ? `${row.userId} · ${row.role}` : row.userId || '—'),
  },
  { title: '来源 IP', key: 'clientIp', width: 140, render: (row) => row.clientIp || '—' },
]

/** 行点击：与 Trace ID 点击一致，打开该行所属链路的详情。 */
function rowProps(row: ApiTraceSpanView): HTMLAttributes {
  return {
    style: { cursor: 'pointer' },
    onClick: () => void openDetail(row.traceId),
  }
}

/** 查询：重置到第一页并加载。 */
function onSearch(): void {
  page.value = 1
  void load()
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await listTraces({
      traceId: filter.traceId.trim() || undefined,
      entry: filter.entry.trim() || undefined,
      status: filter.status || undefined,
      from: dateRange.value?.[0],
      to: dateRange.value?.[1],
      page: page.value,
      size: size.value,
    })
    rows.value = result.items ?? []
    total.value = result.total ?? 0
  } catch (e) {
    message.error((e as Error).message || '加载链路列表失败')
  } finally {
    loading.value = false
  }
}

/** 打开详情抽屉：先清空旧数据再拉取，避免闪现上一条链路。 */
async function openDetail(traceId: string): Promise<void> {
  if (!traceId) return
  detailTraceId.value = traceId
  detailSpans.value = []
  detailVisible.value = true
  detailLoading.value = true
  try {
    detailSpans.value = await getTrace(traceId)
  } catch (e) {
    message.error((e as Error).message || '加载链路详情失败')
  } finally {
    detailLoading.value = false
  }
}

/** epoch 毫秒 → 本地时间字符串。 */
function formatTime(epochMs: number): string {
  return epochMs ? new Date(epochMs).toLocaleString() : '—'
}

onMounted(() => void load())
</script>

<style scoped>
/* 外层内边距由 AdminLayout 内容区统一提供；此处仅约束最小宽度防溢出 */
.traces-page {
  min-width: 0;
}

.traces-page > :not(:last-child) {
  margin-bottom: 14px;
}

/* 筛选控件宽度随断点收缩 */
.filter-trace {
  width: clamp(180px, 30vw, 260px);
}

.filter-entry {
  width: clamp(200px, 32vw, 300px);
}

.filter-range {
  width: clamp(220px, 42vw, 320px);
}

.trace-detail-spin {
  min-height: 160px;
}

/* Trace ID 单元格：省略截断由 NEllipsis 负责，此处只补「可点击」的视觉暗示 */
.trace-id-link {
  cursor: pointer;
}

.trace-id-link:hover {
  text-decoration: underline;
}

.trace-summary {
  margin-bottom: 14px;
}

.span-line {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.span-title {
  font-weight: 600;
  overflow-wrap: anywhere;
}

.span-meta {
  color: #98a2b3;
  font-size: 12px;
}

.span-error {
  margin-top: 4px;
  color: #d03050;
  font-size: 12px;
  overflow-wrap: anywhere;
}
</style>
