<template>
  <!-- 节点配置面板：按节点类型渲染对应字段；文本类字段失焦提交，避免每次按键都入历史栈 -->
  <n-card size="small" title="节点配置" :bordered="true" class="config-card">
    <n-empty v-if="!node" description="在画布中选中一个节点" size="small" />
    <template v-else>
      <n-form size="small" label-placement="top">
        <n-form-item label="节点名称">
          <n-input v-model:value="form.name" placeholder="节点名称" @blur="commit" />
        </n-form-item>

        <template v-if="type === 'retrieve'">
          <n-form-item label="知识库 ID（必填）">
            <n-input v-model:value="form.kb_id" placeholder="如 kb-xxxx" @blur="commit" />
          </n-form-item>
          <n-form-item label="召回候选数（candidate_k）">
            <n-input-number v-model:value="form.candidate_k" :min="1" :max="100" @update:value="commit" />
          </n-form-item>
          <div class="config-tip">候选数由检索器统一配置（`LINGXI_RETRIEVE_CANDIDATE_K`），运行期不生效，仅供记录设计意图。</div>
          <n-form-item label="精排保留数（top_k）">
            <n-input-number v-model:value="form.top_k" :min="1" :max="50" @update:value="commit" />
          </n-form-item>
        </template>

        <template v-else-if="type === 'tool'">
          <n-form-item label="工具（必填）">
            <n-select v-model:value="form.tool_name" :options="toolOptions" @update:value="commit" />
          </n-form-item>
          <n-form-item label="调用超时（毫秒）">
            <n-input-number v-model:value="form.timeout_ms" :min="100" :max="30000" @update:value="commit" />
          </n-form-item>
        </template>

        <template v-else-if="type === 'branch'">
          <n-form-item label="条件表达式（必填）">
            <n-input
              v-model:value="form.expression"
              type="textarea"
              :rows="2"
              placeholder="如 top_score >= 0.6"
              @blur="commit"
            />
          </n-form-item>
          <div class="config-tip">
            条件表达式本期仅存储，由 M4-4.8 运行时评估；请从本节点右侧分别拉出「是 / 否」两条出边。
          </div>
        </template>

        <template v-else>
          <n-form-item label="模型（留空走网关降级链）">
            <n-input v-model:value="form.model" placeholder="如 deepseek-chat" @blur="commit" />
          </n-form-item>
          <n-form-item label="温度（temperature）">
            <n-input-number
              v-model:value="form.temperature"
              :min="0"
              :max="2"
              :step="0.1"
              @update:value="commit"
            />
          </n-form-item>
          <n-form-item label="最大输出（max_tokens）">
            <n-input-number v-model:value="form.max_tokens" :min="1" :max="8192" @update:value="commit" />
          </n-form-item>
          <n-form-item label="系统提示（system_prompt）">
            <n-input
              v-model:value="form.system_prompt"
              type="textarea"
              :rows="3"
              placeholder="可选，留空使用 Agent 默认系统提示"
              @blur="commit"
            />
          </n-form-item>
        </template>
      </n-form>

      <n-space justify="end">
        <n-button size="small" type="error" ghost @click="emit('remove')">删除节点</n-button>
      </n-space>
    </template>
  </n-card>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import {
  NButton,
  NCard,
  NEmpty,
  NForm,
  NFormItem,
  NInput,
  NInputNumber,
  NSelect,
  NSpace,
} from 'naive-ui'
import type { NodeConfig, WorkflowNodeType } from '../../../api/studio'
import { AVAILABLE_TOOLS, type CanvasNode } from '../types'

/**
 * 表单本地状态：数值/文本字段统一用 `| null`，与 naive-ui 组件的 value 类型对齐，
 * 提交时再转回 NodeConfig（null → undefined，避免序列化出 null）。
 */
interface StudioNodeForm {
  name: string
  kb_id: string | null
  candidate_k: number | null
  top_k: number | null
  tool_name: string | null
  timeout_ms: number | null
  expression: string | null
  model: string | null
  temperature: number | null
  max_tokens: number | null
  system_prompt: string | null
}

const props = defineProps<{ node: CanvasNode | null }>()
const emit = defineEmits<{
  /** 提交节点名称与配置（内容未变化时由上层忽略） */
  change: [payload: { id: string; name: string; config: NodeConfig }]
  remove: []
}>()

/** 工具下拉选项：数据源与 AI 域工具注册表保持一致。 */
const toolOptions = AVAILABLE_TOOLS.map((tool) => ({
  label: `${tool.label}（${tool.name}）`,
  value: tool.name,
}))

const form = reactive<StudioNodeForm>({
  name: '',
  kb_id: null,
  candidate_k: null,
  top_k: null,
  tool_name: null,
  timeout_ms: null,
  expression: null,
  model: null,
  temperature: null,
  max_tokens: null,
  system_prompt: null,
})

const type = ref<WorkflowNodeType | null>(null)

watch(
  () => props.node?.id,
  () => {
    const node = props.node
    if (!node) {
      type.value = null
      return
    }
    type.value = node.type as WorkflowNodeType
    const config = node.data.config
    // 先归零再回填：切换节点时不残留上一个节点的字段值
    resetForm()
    Object.assign(form, {
      name: node.data.name,
      kb_id: config.kb_id ?? null,
      candidate_k: config.candidate_k ?? null,
      top_k: config.top_k ?? null,
      tool_name: config.tool_name ?? null,
      timeout_ms: config.timeout_ms ?? null,
      expression: config.expression ?? null,
      model: config.model ?? null,
      temperature: config.temperature ?? null,
      max_tokens: config.max_tokens ?? null,
      system_prompt: config.system_prompt ?? null,
    })
  },
  { immediate: true },
)

/** 清空全部字段（切换选中节点时调用）。 */
function resetForm(): void {
  for (const key of Object.keys(form) as (keyof StudioNodeForm)[]) {
    ;(form[key] as string | number | null) = key === 'name' ? '' : null
  }
}

/** 提交当前表单值。 */
function commit(): void {
  const node = props.node
  if (!node) {
    return
  }
  emit('change', {
    id: node.id,
    name: form.name.trim() || '未命名节点',
    config: buildConfig(),
  })
}

/** 按字段白名单抽取配置，防止把 name 混入 config。 */
function buildConfig(): NodeConfig {
  const config: NodeConfig = {}
  assignDefined(config, 'kb_id', form.kb_id)
  assignDefined(config, 'candidate_k', form.candidate_k)
  assignDefined(config, 'top_k', form.top_k)
  assignDefined(config, 'tool_name', form.tool_name)
  assignDefined(config, 'timeout_ms', form.timeout_ms)
  assignDefined(config, 'expression', form.expression)
  assignDefined(config, 'model', form.model)
  assignDefined(config, 'temperature', form.temperature)
  assignDefined(config, 'max_tokens', form.max_tokens)
  assignDefined(config, 'system_prompt', form.system_prompt)
  return config
}

/** 仅在值非空时写入，保证未填字段不出现在图定义中。 */
function assignDefined(config: NodeConfig, key: keyof NodeConfig, value: string | number | null): void {
  if (value === null || value === '') {
    return
  }
  Object.assign(config, { [key]: value })
}
</script>

<style scoped>
.config-card {
  height: 100%;
  overflow: auto;
}

.config-tip {
  margin-bottom: 12px;
  font-size: 12px;
  color: #999;
  line-height: 1.6;
}
</style>
