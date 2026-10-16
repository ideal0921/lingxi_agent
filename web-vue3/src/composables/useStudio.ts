import { computed, ref } from 'vue'
import type { Connection } from '@vue-flow/core'
import {
  createWorkflow,
  deleteWorkflow,
  getWorkflow,
  listVersions,
  listWorkflows,
  publishWorkflow,
  rollbackVersion,
  saveDraft,
  setRollout,
  type NodeConfig,
  type StudioVersion,
  type StudioWorkflow,
  type WorkflowGraph,
  type WorkflowNodeType,
} from '../api/studio'
import {
  NODE_TYPE_META,
  conditionFromHandle,
  conditionLabel,
  defaultConfig,
  type CanvasEdge,
  type CanvasNode,
} from '../views/studio/types'
import { useAuthStore } from '../stores/auth'

/** 撤销栈上限：超出丢弃栈底，避免长会话内存持续增长。 */
const HISTORY_LIMIT = 50

/**
 * 编排 Studio 状态管理：
 * - 工作流列表 / 当前工作流 / 版本快照；
 * - 画布元素（nodes/edges）与「画布 ↔ 图定义 JSON」双向转换；
 * - 编辑态撤销/重做（快照式栈）与脏标记；
 * - 草稿保存、发布、版本回滚（版本级回滚只改草稿，不新增版本）。
 */
export function useStudio() {
  const auth = useAuthStore()
  // 租户与操作者取自登录态，避免写死导致越权或跨租户
  const tenantId = auth.tenantId
  const operator = auth.user?.username ?? ''

  const workflows = ref<StudioWorkflow[]>([])
  const current = ref<StudioWorkflow | null>(null)
  const versions = ref<StudioVersion[]>([])

  const nodes = ref<CanvasNode[]>([])
  const edges = ref<CanvasEdge[]>([])
  const selectedNodeId = ref<string | null>(null)
  const selectedEdgeId = ref<string | null>(null)

  const dirty = ref(false)
  const busy = ref(false)
  const notice = ref('')
  const error = ref('')

  // 编辑态历史：快照保存「图定义」而非画布对象，避免带上缩放/尺寸等运行时字段
  const history = ref<WorkflowGraph[]>([])
  const future = ref<WorkflowGraph[]>([])
  const canUndo = computed(() => history.value.length > 0)
  const canRedo = computed(() => future.value.length > 0)
  const selectedNode = computed(
    () => nodes.value.find((node) => node.id === selectedNodeId.value) ?? null,
  )

  let idSeq = 0

  /** 生成元素 ID：时间戳 + 自增序号，避免同帧内重复。 */
  function nextId(prefix: string): string {
    idSeq += 1
    return `${prefix}-${Date.now().toString(36)}-${idSeq}`
  }

  /** 画布 → 图定义：只保留持久化契约字段（丢弃画布位置等运行时信息）。 */
  function serialize(): WorkflowGraph {
    return {
      nodes: nodes.value.map((node) => ({
        id: node.id,
        type: node.type as WorkflowNodeType,
        name: node.data.name,
        config: { ...node.data.config },
      })),
      edges: edges.value.map((edge) => ({
        id: edge.id,
        source: edge.source,
        target: edge.target,
        condition: conditionFromHandle(edge.sourceHandle),
      })),
    }
  }

  /** 图定义 → 画布：新导入的节点按网格铺开，避免全部叠在同一坐标。 */
  function hydrate(graph: WorkflowGraph): void {
    nodes.value = graph.nodes.map((node, index) => ({
      id: node.id,
      type: node.type,
      position: { x: 80 + (index % 4) * 240, y: 80 + Math.floor(index / 4) * 160 },
      data: { name: node.name, config: { ...node.config } },
    }))
    edges.value = graph.edges.map((edge) => ({
      id: edge.id,
      source: edge.source,
      target: edge.target,
      // 草稿可能缺 condition（服务端解析时按 always 兜底），此处同样回落 always
      sourceHandle: edge.condition ?? 'always',
      label: conditionLabel(edge.condition) || undefined,
      type: 'smoothstep',
    }))
    clearSelection()
  }

  /** 草稿正文 → 图定义；空草稿或正文损坏时回落为空图，保证编辑器总能打开。 */
  function parseDraft(draft: string): WorkflowGraph {
    if (!draft) {
      return { nodes: [], edges: [] }
    }
    try {
      const parsed = JSON.parse(draft) as Partial<WorkflowGraph>
      return { nodes: parsed.nodes ?? [], edges: parsed.edges ?? [] }
    } catch {
      return { nodes: [], edges: [] }
    }
  }

  /** 拍快照并标记脏：必须在结构性变更「之前」调用，撤销才能回到变更前状态。 */
  function snapshot(): void {
    history.value = [...history.value, structuredClone(serialize())].slice(-HISTORY_LIMIT)
    future.value = []
    dirty.value = true
  }

  /** 应用一次结构性变更：先拍快照再改画布。 */
  function commit(mutate: () => void): void {
    snapshot()
    mutate()
  }

  /** 撤销：把当前图存入重做栈，回到上一个快照。 */
  function undo(): void {
    const previous = history.value.at(-1)
    if (!previous) {
      return
    }
    history.value = history.value.slice(0, -1)
    future.value = [...future.value, structuredClone(serialize())]
    hydrate(previous)
    dirty.value = true
  }

  /** 重做：把当前图存入撤销栈，恢复重做栈顶快照。 */
  function redo(): void {
    const next = future.value.at(-1)
    if (!next) {
      return
    }
    future.value = future.value.slice(0, -1)
    history.value = [...history.value, structuredClone(serialize())]
    hydrate(next)
    dirty.value = true
  }

  /**
   * 节点拖拽落位前拍快照（位置变化同样可撤销）。
   */
  function beginNodeDrag(): void {
    snapshot()
  }

  /** 拖拽新增节点。 */
  function addNodeAt(type: WorkflowNodeType, position: { x: number; y: number }): void {
    commit(() => {
      nodes.value.push({
        id: nextId('n'),
        type,
        position,
        data: { name: NODE_TYPE_META[type].label, config: defaultConfig(type) },
      })
    })
    error.value = ''
  }

  /** 连线：拒绝自环与重复连线；条件标记由源 handle 决定。 */
  function connect(connection: Connection): void {
    const { source, target, sourceHandle } = connection
    if (!source || !target) {
      return
    }
    if (source === target) {
      error.value = '不支持节点自环连线'
      return
    }
    if (edges.value.some((edge) => edge.source === source && edge.target === target)) {
      error.value = '该连线已存在'
      return
    }
    const condition = conditionFromHandle(sourceHandle)
    commit(() => {
      edges.value.push({
        id: nextId('e'),
        source,
        target,
        sourceHandle: condition,
        label: conditionLabel(condition) || undefined,
        type: 'smoothstep',
      })
    })
    error.value = ''
  }

  /** 更新节点名称与配置（内容未变化时不入历史栈）。 */
  function updateNodeData(id: string, data: { name: string; config: NodeConfig }): void {
    const target = nodes.value.find((node) => node.id === id)
    if (!target) {
      return
    }
    const sameName = target.data.name === data.name
    const sameConfig = JSON.stringify(target.data.config) === JSON.stringify(data.config)
    if (sameName && sameConfig) {
      return
    }
    commit(() => {
      target.data = { name: data.name, config: data.config }
    })
  }

  /** 删除当前选中的节点或连线（连带清除关联连线）。 */
  function removeSelected(): boolean {
    if (selectedNodeId.value) {
      const id = selectedNodeId.value
      commit(() => {
        nodes.value = nodes.value.filter((node) => node.id !== id)
        edges.value = edges.value.filter((edge) => edge.source !== id && edge.target !== id)
      })
      clearSelection()
      return true
    }
    if (selectedEdgeId.value) {
      const id = selectedEdgeId.value
      commit(() => {
        edges.value = edges.value.filter((edge) => edge.id !== id)
      })
      clearSelection()
      return true
    }
    return false
  }

  function selectNode(id: string): void {
    selectedNodeId.value = id
    selectedEdgeId.value = null
  }

  function selectEdge(id: string): void {
    selectedEdgeId.value = id
    selectedNodeId.value = null
  }

  function clearSelection(): void {
    selectedNodeId.value = null
    selectedEdgeId.value = null
  }

  /** 刷新工作流列表。 */
  async function refresh(): Promise<void> {
    try {
      workflows.value = await listWorkflows(tenantId)
    } catch {
      error.value = '获取工作流列表失败'
    }
  }

  /** 打开工作流：载入草稿到画布并拉取版本列表，历史栈重置。 */
  async function openWorkflow(workflowId: string): Promise<void> {
    busy.value = true
    try {
      current.value = await getWorkflow(workflowId)
      hydrate(parseDraft(current.value.draft_definition_json))
      history.value = []
      future.value = []
      dirty.value = false
      notice.value = ''
      error.value = ''
      await loadVersions()
    } catch {
      error.value = '打开工作流失败'
    } finally {
      busy.value = false
    }
  }

  /** 新建工作流并直接打开。 */
  async function createNew(name: string, description: string): Promise<boolean> {
    if (!name.trim()) {
      error.value = '工作流名称不能为空'
      return false
    }
    busy.value = true
    try {
      const created = await createWorkflow({
        tenant_id: tenantId,
        name: name.trim(),
        description: description.trim(),
      })
      await refresh()
      await openWorkflow(created.workflow_id)
      notice.value = '工作流已创建，可开始编排'
      return true
    } catch {
      error.value = '新建工作流失败'
      return false
    } finally {
      busy.value = false
    }
  }

  /** 删除工作流（连带清除其版本快照）。 */
  async function removeWorkflow(workflowId: string): Promise<void> {
    busy.value = true
    try {
      await deleteWorkflow(workflowId)
      if (current.value?.workflow_id === workflowId) {
        current.value = null
        nodes.value = []
        edges.value = []
        versions.value = []
        history.value = []
        future.value = []
        dirty.value = false
      }
      await refresh()
    } catch {
      error.value = '删除工作流失败'
    } finally {
      busy.value = false
    }
  }

  /** 拉取当前工作流的版本快照列表。 */
  async function loadVersions(): Promise<void> {
    if (!current.value) {
      versions.value = []
      return
    }
    try {
      versions.value = await listVersions(current.value.workflow_id)
    } catch {
      error.value = '获取版本列表失败'
    }
  }

  /** 保存草稿到服务端（图定义 JSON 由网关原样上送）。 */
  async function saveDraftToServer(): Promise<boolean> {
    if (!current.value) {
      error.value = '请先选择或新建工作流'
      return false
    }
    busy.value = true
    try {
      const result = await saveDraft(current.value.workflow_id, serialize())
      if (!result.ok) {
        error.value = result.reason || '保存草稿失败'
        return false
      }
      if (result.workflow) {
        current.value = result.workflow
      }
      dirty.value = false
      notice.value = '草稿已保存'
      error.value = ''
      return true
    } catch {
      error.value = '保存草稿请求失败'
      return false
    } finally {
      busy.value = false
    }
  }

  /**
   * 发布当前草稿为不可变版本：先落草稿（保证服务端校验的是画布当前内容），
   * 校验不通过时以 reason 提示且不产生版本。
   *
   * @param canary 灰度发布（M4-4.8）：只推进最新版本号，生效版本不变，
   *               随后由 {@link rolloutTo} 指定分流比例
   */
  async function publishToServer(note: string, canary = false): Promise<boolean> {
    if (!current.value) {
      error.value = '请先选择或新建工作流'
      return false
    }
    if (dirty.value && !(await saveDraftToServer())) {
      return false
    }
    busy.value = true
    try {
      const result = await publishWorkflow(current.value.workflow_id, note, operator, canary)
      if (!result.ok) {
        error.value = result.reason || '发布被拒'
        return false
      }
      notice.value = canary
        ? `已灰度发布 v${result.version}（未全量生效，请设置分流比例）`
        : `已发布版本 v${result.version}`
      error.value = ''
      current.value = await getWorkflow(current.value.workflow_id)
      await loadVersions()
      return true
    } catch {
      error.value = '发布请求失败'
      return false
    } finally {
      busy.value = false
    }
  }

  /**
   * 设置灰度分流（M4-4.8）：把指定版本按比例分流给会话（同一会话始终命中同一版本）。
   * `percent=0` 表示关闭灰度并回归生效版本。
   */
  async function rolloutTo(version: number, percent: number): Promise<boolean> {
    if (!current.value) {
      error.value = '请先选择或新建工作流'
      return false
    }
    busy.value = true
    try {
      const result = await setRollout(
        current.value.workflow_id,
        percent > 0 ? version : 0,
        percent,
        operator,
      )
      if (!result.ok) {
        error.value = result.reason || '灰度设置被拒'
        return false
      }
      if (result.workflow) {
        current.value = result.workflow
      }
      notice.value = percent > 0 ? `灰度已设置为 v${version} · ${percent}%` : '灰度已关闭'
      error.value = ''
      return true
    } catch {
      error.value = '灰度设置请求失败'
      return false
    } finally {
      busy.value = false
    }
  }

  /** 回滚：把指定版本内容复制为当前草稿（不新增版本、不改生效版本）。 */
  async function rollbackToVersion(version: number): Promise<boolean> {
    if (!current.value) {
      return false
    }
    busy.value = true
    try {
      const result = await rollbackVersion(current.value.workflow_id, version, operator)
      if (!result.ok) {
        error.value = result.reason || '回滚失败'
        return false
      }
      if (result.workflow) {
        current.value = result.workflow
      }
      hydrate(parseDraft(current.value.draft_definition_json))
      history.value = []
      future.value = []
      dirty.value = false
      notice.value = `已回滚到 v${version}，复核后请重新发布`
      error.value = ''
      return true
    } catch {
      error.value = '回滚请求失败'
      return false
    } finally {
      busy.value = false
    }
  }

  return {
    operator,
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
    loadVersions,
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
  }
}
