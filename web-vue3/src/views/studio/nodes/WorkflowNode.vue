<template>
  <!-- 通用编排节点：按类型配色；分支节点暴露 true/false 两条出边 handle -->
  <div class="wf-node" :style="{ borderColor: meta.color }">
    <Handle type="target" :position="Position.Left" />
    <div class="wf-node-head">
      <span class="wf-node-dot" :style="{ background: meta.color }" />
      <span class="wf-node-title">{{ data.name }}</span>
    </div>
    <div class="wf-node-sub">{{ subtitle }}</div>

    <template v-if="type === 'branch'">
      <Handle
        id="true"
        type="source"
        :position="Position.Right"
        :style="{ top: '35%' }"
        class="wf-handle-true"
      />
      <Handle
        id="false"
        type="source"
        :position="Position.Right"
        :style="{ top: '75%' }"
        class="wf-handle-false"
      />
    </template>
    <Handle v-else id="always" type="source" :position="Position.Right" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Handle, Position, type NodeProps } from '@vue-flow/core'
import { NODE_TYPE_META, describeNode, type CanvasNodeData } from '../types'

const props = defineProps<NodeProps<CanvasNodeData>>()

const meta = computed(() => NODE_TYPE_META[props.type as keyof typeof NODE_TYPE_META])
const subtitle = computed(() => describeNode(props.type as keyof typeof NODE_TYPE_META, props.data.config))
</script>

<style scoped>
.wf-node {
  min-width: 160px;
  padding: 8px 12px;
  background: #fff;
  border: 2px solid #ccc;
  border-radius: 8px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
}

.wf-node-head {
  display: flex;
  align-items: center;
  gap: 6px;
}

.wf-node-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.wf-node-title {
  font-size: 13px;
  font-weight: 600;
}

.wf-node-sub {
  margin-top: 2px;
  font-size: 11px;
  color: #888;
  word-break: break-all;
}
</style>
