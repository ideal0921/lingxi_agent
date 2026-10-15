<template>
  <!-- 节点面板：从四类节点拖拽到画布添加（Vue Flow 无内置外部拖放，由画布自行接管 drop） -->
  <n-card size="small" title="节点面板" :bordered="true" class="palette-card">
    <div
      v-for="type in NODE_TYPES"
      :key="type"
      class="palette-item"
      :style="{ borderLeftColor: NODE_TYPE_META[type].color }"
      draggable="true"
      @dragstart="onDragStart($event, type)"
    >
      <span class="palette-dot" :style="{ background: NODE_TYPE_META[type].color }" />
      <span>{{ NODE_TYPE_META[type].label }}</span>
    </div>
    <div class="palette-tip">按住并拖拽到画布即可添加节点</div>
  </n-card>
</template>

<script setup lang="ts">
import { NCard } from 'naive-ui'
import type { WorkflowNodeType } from '../../../api/studio'
import { NODE_TYPE_META, NODE_TYPES, STUDIO_DRAG_MIME } from '../types'

/** 拖拽开始：写入自定义 MIME，画布 drop 时据此还原节点类型。 */
function onDragStart(event: DragEvent, type: WorkflowNodeType): void {
  if (!event.dataTransfer) {
    return
  }
  event.dataTransfer.setData(STUDIO_DRAG_MIME, type)
  event.dataTransfer.effectAllowed = 'move'
}
</script>

<style scoped>
.palette-card {
  height: 100%;
}

.palette-item {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  padding: 8px 10px;
  font-size: 13px;
  background: #f7f8fa;
  border: 1px solid #eee;
  border-left: 4px solid #ccc;
  border-radius: 6px;
  cursor: grab;
  user-select: none;
}

.palette-item:hover {
  background: #eef3f9;
}

.palette-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.palette-tip {
  margin-top: 12px;
  font-size: 12px;
  color: #999;
  line-height: 1.6;
}
</style>
