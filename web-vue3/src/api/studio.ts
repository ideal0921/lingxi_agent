import http from './http'

/**
 * 编排 Studio API：对接 Go 网关 /api/v1/studio/*（网关转发 Java 编排域）。
 * 约定：http 响应拦截器已解包为响应体，此处按业务结构断言类型。
 */

/** 节点类型（与编排域图定义 JSON Schema 一致）。 */
export type WorkflowNodeType = 'llm' | 'retrieve' | 'tool' | 'branch'

/** 边条件标记：分支节点出边为 true/false，其余节点出边为 always。 */
export type EdgeCondition = 'always' | 'true' | 'false'

/**
 * 节点配置（开放键值，按类型取用）。
 * 必填项：retrieve→kb_id、tool→tool_name、branch→expression；llm 全部可选。
 */
export interface NodeConfig {
  /** 检索节点：知识库 ID（必填） */
  kb_id?: string
  /** 检索节点：召回候选数（默认 10） */
  candidate_k?: number
  /** 检索节点：精排保留数（默认 5） */
  top_k?: number
  /** 工具节点：工具名（必填，取自 ai-python 工具注册表） */
  tool_name?: string
  /** 工具节点：调用超时（毫秒） */
  timeout_ms?: number
  /** 分支节点：条件表达式（必填；本期仅存储，由 M4-4.8 运行时求值） */
  expression?: string
  /** LLM 节点：模型留空表示走网关降级链 */
  model?: string
  temperature?: number
  max_tokens?: number
  system_prompt?: string
}

/** 图定义节点（持久化契约）。 */
export interface WorkflowNodeDto {
  id: string
  type: WorkflowNodeType
  name: string
  config: NodeConfig
}

/** 图定义连线（持久化契约）。 */
export interface WorkflowEdgeDto {
  id: string
  source: string
  target: string
  condition: EdgeCondition
}

/** 工作流图定义：跨语言唯一契约，M4-4.8 运行时按此加载。 */
export interface WorkflowGraph {
  nodes: WorkflowNodeDto[]
  edges: WorkflowEdgeDto[]
}

/** 工作流主体（列表不含草稿正文，详情含）。 */
export interface StudioWorkflow {
  workflow_id: string
  tenant_id: string
  name: string
  description: string
  status: string
  draft_definition_json: string
  latest_version: number
  published_version: number
  /** 灰度版本号（M4-4.8；0 表示未启用灰度） */
  canary_version: number
  /** 灰度分流比例 0~100（0 表示关闭灰度） */
  canary_percent: number
  created_at_ms: number
  updated_at_ms: number
}

/** 版本快照摘要（列表用，不含图定义正文）。 */
export interface StudioVersion {
  version: number
  note: string
  operator: string
  published_at_ms: number
  created_at_ms: number
}

/** 版本快照详情（含图定义正文）。 */
export interface StudioVersionDetail {
  ok: boolean
  reason: string
  version: number
  definition_json: string
  note: string
  operator: string
  published_at_ms: number
}

/** 草稿保存 / 回滚等带业务结果的响应。 */
export interface StudioMutationResult {
  ok: boolean
  reason: string
  workflow?: StudioWorkflow
}

/** 发布结果：ok=false 时 reason 为校验拒绝原因。 */
export interface StudioPublishResult {
  ok: boolean
  reason: string
  version: number
}

/** 按租户列出工作流。 */
export async function listWorkflows(tenantId: string): Promise<StudioWorkflow[]> {
  const resp = (await http.get('/studio/workflows', { params: { tenant_id: tenantId } })) as {
    workflows: StudioWorkflow[]
  }
  return resp.workflows ?? []
}

/** 新建工作流。 */
export async function createWorkflow(payload: {
  tenant_id: string
  name: string
  description?: string
}): Promise<StudioWorkflow> {
  const resp = (await http.post('/studio/workflows', payload)) as { workflow: StudioWorkflow }
  return resp.workflow
}

/** 查询工作流详情（含当前草稿）。 */
export async function getWorkflow(workflowId: string): Promise<StudioWorkflow> {
  const resp = (await http.get(`/studio/workflows/${workflowId}`)) as { workflow: StudioWorkflow }
  return resp.workflow
}

/** 保存草稿：直接上送画布图对象，由网关原样转成 JSON 文本。 */
export async function saveDraft(
  workflowId: string,
  definition: WorkflowGraph,
): Promise<StudioMutationResult> {
  return (await http.put(`/studio/workflows/${workflowId}/draft`, { definition })) as StudioMutationResult
}

/**
 * 发布当前草稿为不可变版本。
 * canary=true 为灰度发布（M4-4.8）：只推进最新版本号，生效版本保持不变，
 * 随后由 setRollout 指定分流比例。
 */
export async function publishWorkflow(
  workflowId: string,
  note: string,
  operator: string,
  canary = false,
): Promise<StudioPublishResult> {
  return (await http.post(`/studio/workflows/${workflowId}/publish`, {
    note,
    operator,
    canary,
  })) as StudioPublishResult
}

/**
 * 设置灰度分流（M4-4.8）：canaryPercent=0 表示关闭灰度（灰度版本归 0）。
 * 比例越界或灰度版本不存在时由服务端拒绝（ok=false + reason）。
 */
export async function setRollout(
  workflowId: string,
  canaryVersion: number,
  canaryPercent: number,
  operator: string,
): Promise<StudioMutationResult> {
  return (await http.post(`/studio/workflows/${workflowId}/rollout`, {
    canary_version: canaryVersion,
    canary_percent: canaryPercent,
    operator,
  })) as StudioMutationResult
}

/** 列出版本快照（版本号倒序）。 */
export async function listVersions(workflowId: string): Promise<StudioVersion[]> {
  const resp = (await http.get(`/studio/workflows/${workflowId}/versions`)) as {
    versions: StudioVersion[]
  }
  return resp.versions ?? []
}

/** 查询某版本快照正文（只读，不影响草稿）。 */
export async function getVersion(workflowId: string, version: number): Promise<StudioVersionDetail> {
  return (await http.get(`/studio/workflows/${workflowId}/versions/${version}`)) as StudioVersionDetail
}

/** 回滚：把指定版本内容复制为当前草稿（不新增版本、不改生效版本）。 */
export async function rollbackVersion(
  workflowId: string,
  version: number,
  operator: string,
): Promise<StudioMutationResult> {
  return (await http.post(`/studio/workflows/${workflowId}/versions/${version}/rollback`, {
    operator,
  })) as StudioMutationResult
}

/** 删除工作流（连带清除版本快照）。 */
export async function deleteWorkflow(workflowId: string): Promise<StudioMutationResult> {
  return (await http.delete(`/studio/workflows/${workflowId}`)) as StudioMutationResult
}
