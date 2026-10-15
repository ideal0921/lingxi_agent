<template>
  <!-- M4-4.7 编排 Studio：拖拽节点（LLM/检索/工具/分支）→ 配置 → 发布版本 → 可回滚 -->
  <div class="studio">
    <!-- 顶部工具栏 -->
    <header class="studio-bar">
      <n-space align="center" :size="8">
        <!-- 显式 key：n-space 内部按索引维护子节点 key，条件渲染增减子项会触发重复 key 告警 -->
        <span key="title" class="studio-title">Agent 编排 Studio</span>
        <n-select
          key="workflow"
          v-model:value="activeId"
          :options="workflowOptions"
          :disabled="busy"
          placeholder="选择工作流"
          size="small"
          class="studio-select"
        />
        <n-button key="create" size="small" @click="showCreate = true">新建</n-button>
        <n-button
          key="remove"
          size="small"
          :disabled="!current || busy"
          @click="current && removeWorkflow(current.workflow_id)"
        >
          删除
        </n-button>
      </n-space>

      <n-space align="center" :size="8">
        <n-tag
          v-if="current"
          key="state"
          size="small"
          :type="current.published_version > 0 ? 'success' : 'warning'"
        >
          {{ current.published_version > 0 ? `生效 v${current.published_version}` : '未发布' }}
        </n-tag>
        <n-tag
          v-if="current && current.canary_percent > 0"
          key="canary"
          size="small"
          type="info"
        >
          {{ `灰度 v${current.canary_version} · ${current.canary_percent}%` }}
        </n-tag>
        <n-tag v-if="dirty" key="dirty" size="small" type="warning">未保存</n-tag>
        <n-button key="undo" size="small" :disabled="!canUndo" @click="undo">撤销</n-button>
        <n-button key="redo" size="small" :disabled="!canRedo" @click="redo">重做</n-button>
        <n-button
          key="remove-selected"
          size="small"
          :disabled="!removeEnabled"
          @click="removeSelected"
        >
          删除选中
        </n-button>
        <n-button
          key="save"
          size="small"
          :disabled="!current || busy"
          :loading="busy"
          @click="saveDraftToServer"
        >
          保存草稿
        </n-button>
        <n-button
          key="publish"
          size="small"
          type="primary"
          :disabled="!current || busy"
          @click="showPublish = true"
        >
          发布
        </n-button>
        <n-button key="versions" size="small" :disabled="!current" @click="showVersions = true">
          版本
        </n-button>
      </n-space>
    </header>

    <!-- 三栏：节点面板 / 画布 / 节点配置 -->
    <div class="studio-body">
      <aside class="studio-side">
        <NodePalette />
      </aside>

      <main class="studio-canvas" @dragover.prevent @drop="onDrop">
        <VueFlow
          v-model:nodes="nodes"
          v-model:edges="edges"
          :delete-key-code="null"
          :min-zoom="0.3"
          :max-zoom="2"
          @connect="connect"
          @node-click="onNodeClick"
          @edge-click="onEdgeClick"
          @pane-click="clearSelection"
          @node-drag-start="beginNodeDrag"
        >
          <Background />
          <Controls />
          <MiniMap />
          <template #node-llm="nodeProps">
            <WorkflowNode v-bind="nodeProps" />
          </template>
          <template #node-retrieve="nodeProps">
            <WorkflowNode v-bind="nodeProps" />
          </template>
          <template #node-tool="nodeProps">
            <WorkflowNode v-bind="nodeProps" />
          </template>
          <template #node-branch="nodeProps">
            <WorkflowNode v-bind="nodeProps" />
          </template>
        </VueFlow>
      </main>

      <aside class="studio-side">
        <NodeConfigPanel :node="selectedNode" @change="onNodeDataChange" @remove="removeSelected" />
      </aside>
    </div>

    <n-alert
      v-if="error"
      class="studio-alert"
      type="warning"
      :show-icon="false"
      closable
      @close="error = ''"
    >
      {{ error }}
    </n-alert>
    <n-alert
      v-else-if="notice"
      class="studio-alert"
      type="success"
      :show-icon="false"
      closable
      @close="notice = ''"
    >
      {{ notice }}
    </n-alert>

    <!-- 新建工作流 -->
    <n-modal
      v-model:show="showCreate"
      preset="card"
      title="新建工作流"
      style="width: 420px"
    >
      <n-form size="small" label-placement="top">
        <n-form-item label="名称（必填）">
          <n-input v-model:value="newName" placeholder="如 售后退款编排" />
        </n-form-item>
        <n-form-item label="描述">
          <n-input v-model:value="newDesc" type="textarea" :rows="2" placeholder="可选" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button size="small" @click="showCreate = false">取消</n-button>
          <n-button size="small" type="primary" :disabled="busy" @click="submitCreate">创建</n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- 发布 -->
    <n-modal v-model:show="showPublish" preset="card" title="发布工作流" style="width: 440px">
      <n-input v-model:value="publishNote" type="textarea" :rows="2" placeholder="发布说明（可选）" />
      <!-- 灰度发布（M4-4.8）：只推进最新版本号，生效版本不变，由灰度分流按比例放量 -->
      <n-checkbox v-model:checked="publishCanary" class="modal-checkbox">
        灰度发布（不立即生效，由「版本 → 灰度分流」按比例放量）
      </n-checkbox>
      <div class="modal-tip">发布前会对画布做完整校验（入口唯一、无环、分支出边配对、必填配置）。</div>
      <template #footer>
        <n-space justify="end">
          <n-button size="small" @click="showPublish = false">取消</n-button>
          <n-button size="small" type="primary" :disabled="busy" @click="submitPublish">发布</n-button>
        </n-space>
      </template>
    </n-modal>

    <VersionDrawer
      v-model:show="showVersions"
      :workflow-id="current?.workflow_id ?? ''"
      :versions="versions"
      :current="current"
      @rollback="onRollback"
      @rollout="onRollout"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { VueFlow, useVueFlow, type EdgeMouseEvent, type NodeMouseEvent } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { MiniMap } from '@vue-flow/minimap'
import {
  NAlert,
  NButton,
  NCheckbox,
  NForm,
  NFormItem,
  NInput,
  NModal,
  NSelect,
  NSpace,
  NTag,
} from 'naive-ui'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import '@vue-flow/minimap/dist/style.css'
import type { NodeConfig, WorkflowNodeType } from '../../api/studio'
import { useStudio } from '../../composables/useStudio'
import NodePalette from './components/NodePalette.vue'
import NodeConfigPanel from './components/NodeConfigPanel.vue'
import VersionDrawer from './components/VersionDrawer.vue'
import WorkflowNode from './nodes/WorkflowNode.vue'
import { STUDIO_DRAG_MIME } from './types'

const {
  workflows,
  current,
  versions,
  nodes,
  edges,
  selectedNode,
  selectedEdgeId,
  dirty,
  busy,
  notice,
  error,
  canUndo,
  canRedo,
  refresh,
  openWorkflow,
  createNew,
  removeWorkflow,
  saveDraftToServer,
  publishToServer,
  rolloutTo,
  rollbackToVersion,
  addNodeAt,
  connect,
  beginNodeDrag,
  updateNodeData,
  removeSelected,
  selectNode,
  selectEdge,
  clearSelection,
  undo,
  redo,
} = useStudio()

const { screenToFlowCoordinate, fitView } = useVueFlow()

const activeId = ref<string | null>(null)
const showCreate = ref(false)
const showPublish = ref(false)
const showVersions = ref(false)
const newName = ref('')
const newDesc = ref('')
const publishNote = ref('')
// 灰度发布开关（M4-4.8）：勾选后 latest 前进但生效版本不变
const publishCanary = ref(false)

const workflowOptions = computed(() =>
  workflows.value.map((item) => ({
    label: item.published_version > 0
      ? `${item.name}（生效 v${item.published_version}）`
      : `${item.name}（未发布）`,
    value: item.workflow_id,
  })),
)

/** 删除按钮可用性：未选中任何元素时不可用。 */
const removeEnabled = computed(() => !!selectedNode.value || !!selectedEdgeId.value)

onMounted(async () => {
  await refresh()
  // 默认打开第一条工作流：编辑器首屏不应是空画布（未选择时给出可编排的起点）
  const first = workflows.value[0]
  if (first) {
    activeId.value = first.workflow_id
  }
  window.addEventListener('keydown', onKeydown)
})

onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown)
})

// 工作流切换：由 activeId 单一驱动加载（避免与 v-model 重复监听同一事件）
watch(activeId, (id) => {
  if (id && id !== current.value?.workflow_id) {
    void openWorkflow(id)
  }
})

// 切换工作流后自动适配视图，避免新打开的画布落在视野外
watch(
  () => current.value?.workflow_id,
  async (id) => {
    if (!id) {
      return
    }
    await nextTick()
    await fitView({ padding: 0.2 })
  },
)

/** 提交新建。 */
async function submitCreate(): Promise<void> {
  const ok = await createNew(newName.value, newDesc.value)
  if (ok) {
    showCreate.value = false
    newName.value = ''
    newDesc.value = ''
    activeId.value = current.value?.workflow_id ?? null
  }
}

/** 提交发布（先落草稿，服务端校验不通过则以 reason 提示）。 */
async function submitPublish(): Promise<void> {
  const ok = await publishToServer(publishNote.value, publishCanary.value)
  if (ok) {
    showPublish.value = false
    publishNote.value = ''
    publishCanary.value = false
  }
}

/** 保存灰度分流（比例为 0 表示关闭灰度并回归生效版本）。 */
async function onRollout(payload: { version: number; percent: number }): Promise<void> {
  await rolloutTo(payload.version, payload.percent)
}

/** 回滚到历史版本（只改草稿，不新增版本）。 */
async function onRollback(version: number): Promise<void> {
  const ok = await rollbackToVersion(version)
  if (ok) {
    showVersions.value = false
  }
}

/** 画布拖入：从面板拖拽的节点按落点坐标新建。 */
function onDrop(event: DragEvent): void {
  const type = event.dataTransfer?.getData(STUDIO_DRAG_MIME)
  if (!type) {
    return
  }
  const position = screenToFlowCoordinate({ x: event.clientX, y: event.clientY })
  addNodeAt(type as WorkflowNodeType, position)
}

function onNodeClick(event: NodeMouseEvent): void {
  selectNode(event.node.id)
}

function onEdgeClick(event: EdgeMouseEvent): void {
  selectEdge(event.edge.id)
}

/** 配置面板提交：写入画布节点数据（内容未变化时由 composable 忽略）。 */
function onNodeDataChange(payload: { id: string; name: string; config: NodeConfig }): void {
  updateNodeData(payload.id, { name: payload.name, config: payload.config })
}

/** 全局快捷键：Ctrl/Cmd+Z 撤销、Ctrl/Cmd+Shift+Z 重做、Ctrl/Cmd+S 保存草稿。 */
function onKeydown(event: KeyboardEvent): void {
  const modifier = event.ctrlKey || event.metaKey
  if (!modifier) {
    return
  }
  const key = event.key.toLowerCase()
  if (key === 'z') {
    event.preventDefault()
    if (event.shiftKey) {
      redo()
    } else {
      undo()
    }
    return
  }
  if (key === 's') {
    event.preventDefault()
    if (current.value && !busy.value) {
      void saveDraftToServer()
    }
  }
}
</script>

<style scoped>
.studio {
  display: flex;
  flex-direction: column;
  height: 100vh;
  box-sizing: border-box;
  background: #f5f6f8;
  /* 窄视口下保持三栏最小宽度并横向滚动，避免画布被压塌 */
  min-width: 1024px;
}

.studio-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  background: #fff;
  border-bottom: 1px solid #e8e8e8;
}

.studio-title {
  font-size: 14px;
  font-weight: 600;
}

.studio-select {
  width: 240px;
}

.studio-body {
  display: flex;
  flex: 1 1 auto;
  gap: 12px;
  padding: 12px;
  overflow: hidden;
}

.studio-side {
  width: 260px;
  flex: 0 0 260px;
  overflow: hidden;
}

.studio-canvas {
  flex: 1 1 auto;
  position: relative;
  overflow: hidden;
  background: #fff;
  border: 1px solid #e8e8e8;
  border-radius: 6px;
}

.studio-alert {
  position: fixed;
  bottom: 16px;
  left: 50%;
  transform: translateX(-50%);
  width: 460px;
  z-index: 10;
}

.modal-tip {
  margin-top: 10px;
  font-size: 12px;
  color: #999;
  line-height: 1.6;
}

.modal-checkbox {
  margin-top: 10px;
}
</style>
