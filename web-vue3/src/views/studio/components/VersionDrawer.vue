<template>
  <!-- 版本抽屉：版本快照列表 + 查看历史图定义（只读）+ 回滚到指定版本 -->
  <n-drawer :show="show" :width="480" @update:show="emit('update:show', $event)">
    <n-drawer-content closable>
      <template #header>版本快照</template>

      <!-- 灰度分流（M4-4.8）：按会话稳定哈希把流量分给灰度版本；比例为 0 即关闭 -->
      <n-card size="small" title="灰度分流" :bordered="true" class="rollout-card">
        <n-space vertical :size="8">
          <n-space align="center" :size="8">
            <span class="rollout-label">灰度版本</span>
            <n-select
              v-model:value="rolloutVersion"
              :options="rolloutOptions"
              size="small"
              class="rollout-select"
              placeholder="选择版本"
            />
          </n-space>
          <n-space align="center" :size="8">
            <span class="rollout-label">分流比例</span>
            <n-input-number
              v-model:value="rolloutPercent"
              size="small"
              :min="0"
              :max="100"
              class="rollout-percent"
            />
            <span class="rollout-label">%</span>
          </n-space>
          <n-space :size="8">
            <n-button size="tiny" type="primary" :disabled="!workflowId" @click="submitRollout">
              保存灰度
            </n-button>
            <n-button size="tiny" :disabled="!workflowId || rolloutPercent === 0" @click="closeRollout">
              关闭灰度
            </n-button>
          </n-space>
          <div class="rollout-tip">
            当前：{{ currentRolloutText }}；同一会话始终命中同一版本（稳定哈希分桶）。
          </div>
        </n-space>
      </n-card>

      <n-empty v-if="versions.length === 0" description="尚未发布任何版本" size="small" />
      <div v-for="item in versions" :key="item.version" class="version-item">
        <div class="version-head">
          <span class="version-tag">v{{ item.version }}</span>
          <span class="version-time">{{ formatTime(item.published_at_ms) }}</span>
        </div>
        <div class="version-note">{{ item.note || '（无发布说明）' }}</div>
        <div class="version-meta">操作者：{{ item.operator || '—' }}</div>
        <n-space :size="8" style="margin-top: 6px">
          <n-button size="tiny" @click="preview(item.version)">查看</n-button>
          <n-button size="tiny" @click="setAsCanary(item.version)">设为灰度</n-button>
          <n-button size="tiny" type="warning" ghost @click="emit('rollback', item.version)">
            回滚到 v{{ item.version }}
          </n-button>
        </n-space>
      </div>

      <template #footer>
        <span class="drawer-tip">回滚只把历史版本内容复制为当前草稿，不新增版本、不改生效版本；复核后重新发布即生成新版本号。</span>
      </template>
    </n-drawer-content>
  </n-drawer>

  <!-- 快照预览：只读展示历史图定义（不做画布渲染，避免误以为已应用） -->
  <n-modal
    :show="!!previewDetail"
    preset="card"
    title="历史版本图定义（只读）"
    style="width: 640px"
    @update:show="closePreview"
  >
    <n-alert v-if="previewError" type="warning" :show-icon="false">{{ previewError }}</n-alert>
    <template v-else-if="previewDetail">
      <div class="preview-meta">
        v{{ previewDetail.version }} · {{ previewDetail.note || '（无发布说明）' }} ·
        {{ formatTime(previewDetail.published_at_ms) }}
      </div>
      <n-code :code="previewDetail.definition_json" language="json" show-line-numbers word-wrap />
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  NAlert,
  NButton,
  NCard,
  NCode,
  NDrawer,
  NDrawerContent,
  NEmpty,
  NInputNumber,
  NModal,
  NSelect,
  NSpace,
} from 'naive-ui'
import {
  getVersion,
  type StudioVersion,
  type StudioVersionDetail,
  type StudioWorkflow,
} from '../../../api/studio'

const props = defineProps<{
  show: boolean
  workflowId: string
  versions: StudioVersion[]
  /** 当前工作流（读取灰度版本与比例；未打开时为空） */
  current?: StudioWorkflow | null
}>()

const emit = defineEmits<{
  'update:show': [value: boolean]
  rollback: [version: number]
  /** 保存灰度分流（版本号 + 比例；比例为 0 表示关闭） */
  rollout: [payload: { version: number; percent: number }]
}>()

const previewDetail = ref<StudioVersionDetail | null>(null)
const previewError = ref('')

const rolloutVersion = ref<number | null>(null)
const rolloutPercent = ref<number | null>(0)

/** 可选灰度版本：仅已发布的快照。 */
const rolloutOptions = computed(() =>
  props.versions.map((item) => ({ label: `v${item.version}`, value: item.version })),
)

/** 当前灰度规则文案（供运营确认生效状态）。 */
const currentRolloutText = computed(() => {
  const workflow = props.current
  if (!workflow || workflow.canary_percent <= 0) {
    return '未启用灰度'
  }
  return `v${workflow.canary_version} · ${workflow.canary_percent}%`
})

// 打开抽屉时同步当前灰度规则到表单，避免误操作改动线上配置
watch(
  () => props.show,
  (visible) => {
    if (!visible) {
      return
    }
    rolloutVersion.value = props.current?.canary_version || null
    rolloutPercent.value = props.current?.canary_percent ?? 0
  },
)

/** 提交灰度设置（比例 0 时同时把灰度版本归 0 由服务端处理）。 */
function submitRollout(): void {
  const percent = rolloutPercent.value ?? 0
  const version = rolloutVersion.value ?? 0
  emit('rollout', { version, percent })
}

/** 关闭灰度（比例归 0）。 */
function closeRollout(): void {
  rolloutPercent.value = 0
  emit('rollout', { version: 0, percent: 0 })
}

/** 快捷把某版本设为灰度版本（沿用当前比例，默认 10%）。 */
function setAsCanary(version: number): void {
  rolloutVersion.value = version
  if (!rolloutPercent.value) {
    rolloutPercent.value = 10
  }
}

/** 查看历史版本正文（只读）。 */
async function preview(version: number): Promise<void> {
  previewError.value = ''
  previewDetail.value = null
  try {
    const detail = await getVersion(props.workflowId, version)
    if (!detail.ok) {
      previewError.value = detail.reason || '版本不存在'
      return
    }
    previewDetail.value = detail
  } catch {
    previewError.value = '查询版本失败'
    previewDetail.value = null
  }
}

function closePreview(value: boolean): void {
  if (!value) {
    previewDetail.value = null
    previewError.value = ''
  }
}

/** epoch ms → 本地时间字符串。 */
function formatTime(ms: number): string {
  return ms > 0 ? new Date(ms).toLocaleString('zh-CN') : '—'
}
</script>

<style scoped>
.version-item {
  padding: 10px;
  margin-bottom: 10px;
  border: 1px solid #eee;
  border-radius: 6px;
}

.rollout-card {
  margin-bottom: 12px;
}

.rollout-label {
  font-size: 12px;
  color: #666;
}

.rollout-select {
  width: 120px;
}

.rollout-percent {
  width: 120px;
}

.rollout-tip {
  font-size: 12px;
  color: #999;
  line-height: 1.6;
}

.version-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.version-tag {
  font-size: 13px;
  font-weight: 600;
  color: #2b6cb0;
}

.version-time {
  font-size: 12px;
  color: #999;
}

.version-note {
  margin-top: 4px;
  font-size: 13px;
  word-break: break-word;
}

.version-meta {
  margin-top: 2px;
  font-size: 12px;
  color: #888;
}

.drawer-tip {
  font-size: 12px;
  color: #999;
  line-height: 1.6;
}

.preview-meta {
  margin-bottom: 8px;
  font-size: 12px;
  color: #888;
}
</style>
