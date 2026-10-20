<template>
  <!-- 菜单图标：按 menu.ts 的 key 渲染 18px 线性内联 SVG（currentColor 描边，无外链依赖） -->
  <svg
    width="18"
    height="18"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.7"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <template v-if="name === 'portal'">
      <path d="M4 5h16a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H9l-5 4v-4H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z" />
    </template>
    <template v-else-if="name === 'workbench'">
      <path d="M4 13v-1a8 8 0 0 1 16 0v1" />
      <rect x="3" y="13" width="4" height="6" rx="2" />
      <rect x="17" y="13" width="4" height="6" rx="2" />
    </template>
    <template v-else-if="name === 'report'">
      <path d="M5 20V11" />
      <path d="M12 20V4" />
      <path d="M19 20v-6" />
    </template>
    <template v-else-if="name === 'studio'">
      <circle cx="5.5" cy="6" r="2.4" />
      <circle cx="18.5" cy="12" r="2.4" />
      <circle cx="5.5" cy="18" r="2.4" />
      <path d="M7.9 6h8.2" />
      <path d="M7.9 18h5.1a3 3 0 0 0 3-3v-3" />
    </template>
    <template v-else-if="name === 'kb'">
      <!-- 知识库：叠放的书本 / 数据库样式，寓意文档集合 -->
      <ellipse cx="12" cy="5.5" rx="7.5" ry="3" />
      <path d="M4.5 5.5v6c0 1.66 3.36 3 7.5 3s7.5-1.34 7.5-3v-6" />
      <path d="M4.5 11.5v6c0 1.66 3.36 3 7.5 3s7.5-1.34 7.5-3v-6" />
    </template>
    <template v-else-if="name === 'logs'">
      <path d="M8 6h13" />
      <path d="M8 12h13" />
      <path d="M8 18h13" />
      <path d="M3.6 6h.01" />
      <path d="M3.6 12h.01" />
      <path d="M3.6 18h.01" />
    </template>
    <template v-else-if="name === 'traces'">
      <!-- 接口链路追踪：三节点由连线串联，寓意网关→Java→Python 的调用链 -->
      <circle cx="6" cy="6" r="2.2" />
      <circle cx="6" cy="18" r="2.2" />
      <circle cx="18" cy="12" r="2.2" />
      <path d="M6 8.2v7.6" />
      <path d="M8.1 6.9l7.9 4.4" />
      <path d="M8.1 17.1l7.9-4.4" />
    </template>
    <template v-else-if="name === 'users'">
      <circle cx="9" cy="8" r="3.2" />
      <path d="M3.5 19.5a5.5 5.5 0 0 1 11 0" />
      <path d="M16 7.2a3 3 0 0 1 0 5.6" />
      <path d="M17.6 19.5a5.5 5.5 0 0 0-2.1-4.3" />
    </template>
    <template v-else>
      <circle cx="12" cy="12" r="9" />
    </template>
  </svg>
</template>

<script setup lang="ts">
defineProps<{ name: string }>()
</script>
