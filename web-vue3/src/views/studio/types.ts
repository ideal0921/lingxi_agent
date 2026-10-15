import type { EdgeCondition, NodeConfig, WorkflowNodeType } from '../../api/studio'

/** 画布节点承载的业务数据：名称 + 节点配置（位置由画布维护，序列化时不入图定义）。 */
export interface CanvasNodeData {
  name: string
  config: NodeConfig
}

/**
 * 画布节点 / 连线：刻意使用轻量结构而非 Vue Flow 的 `Node`/`Edge` 泛型——
 * 直接实例化其深度递归类型会触发 TS「类型实例化过深」，此处仅声明与画布元素结构兼容的字段。
 */
export interface CanvasNode {
  id: string
  type?: string
  position: { x: number; y: number }
  data: CanvasNodeData
}

export interface CanvasEdge {
  id: string
  source: string
  target: string
  sourceHandle?: string | null
  targetHandle?: string | null
  label?: string
  type?: string
}

/** 节点类型元信息：面板标题与配色。 */
export interface NodeTypeMeta {
  label: string
  color: string
}

export const NODE_TYPE_META: Record<WorkflowNodeType, NodeTypeMeta> = {
  llm: { label: 'LLM 生成', color: '#2b6cb0' },
  retrieve: { label: '知识检索', color: '#2f855a' },
  tool: { label: '工具调用', color: '#b7791f' },
  branch: { label: '条件分支', color: '#805ad5' },
}

/** 节点面板顺序（与需求描述一致：LLM / 检索 / 工具 / 分支）。 */
export const NODE_TYPES: WorkflowNodeType[] = ['llm', 'retrieve', 'tool', 'branch']

/**
 * 面板拖拽载荷 MIME 类型。
 * Vue Flow v1 未内置「外部拖入」协议，画布 drop 处理与本常量约定一致。
 */
export const STUDIO_DRAG_MIME = 'application/lingxi-studio-node'

/** 可选业务工具（与 ai-python 工具注册表保持一致）。 */
export const AVAILABLE_TOOLS = [
  { name: 'get_order', label: '查询订单' },
  { name: 'get_logistics', label: '查询物流' },
  { name: 'submit_return', label: '提交退换货' },
]

/** 节点默认配置：与后端必填项对齐，减少手工填写。 */
export function defaultConfig(type: WorkflowNodeType): NodeConfig {
  switch (type) {
    case 'llm':
      return { model: '', temperature: 0.7, max_tokens: 1024, system_prompt: '' }
    case 'retrieve':
      return { kb_id: '', candidate_k: 10, top_k: 5 }
    case 'tool':
      return { tool_name: AVAILABLE_TOOLS[0].name, timeout_ms: 5000 }
    case 'branch':
      return { expression: 'top_score >= 0.6' }
  }
}

/** 节点配置摘要（画布卡片副标题，便于一眼看出配置是否完整）。 */
export function describeNode(type: WorkflowNodeType, config: NodeConfig): string {
  switch (type) {
    case 'llm':
      return `${config.model || '默认模型'} · T=${config.temperature ?? 0.7}`
    case 'retrieve':
      return config.kb_id ? `库 ${config.kb_id} · Top${config.top_k ?? 5}` : '未配置知识库'
    case 'tool':
      return config.tool_name || '未配置工具'
    case 'branch':
      return config.expression || '未配置条件'
  }
}

/** 条件标记 → 画布连线标签（always 无边标签）。 */
export function conditionLabel(condition: EdgeCondition): string {
  if (condition === 'true') return '是'
  if (condition === 'false') return '否'
  return ''
}

/** 连线 handle id → 条件标记（普通边的 handle 为 always）。 */
export function conditionFromHandle(handle?: string | null): EdgeCondition {
  if (handle === 'true' || handle === 'false') return handle
  return 'always'
}
