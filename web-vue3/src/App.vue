<template>
  <!-- 全局保持浅色主题（既有工作台/报表页依赖）；登录页与后台框架在各自组件内局部暗色覆盖 -->
  <n-config-provider :theme-overrides="themeOverrides">
    <n-message-provider>
      <n-dialog-provider>
        <router-view />
      </n-dialog-provider>
    </n-message-provider>
  </n-config-provider>
</template>

<script setup lang="ts">
// 根组件：全局配置 Naive UI 主题与消息/对话框容器
import {
  NConfigProvider,
  NDialogProvider,
  NMessageProvider,
  type GlobalThemeOverrides,
} from 'naive-ui'

const themeOverrides: GlobalThemeOverrides = {
  common: {
    primaryColor: '#2b6cb0',
  },
}
</script>

<style>
/* 全局基线（非 scoped）：统一盒模型与溢出行为，避免 flex 子项被内容撑破、长文本横向溢出 */
html,
body,
#app {
  height: 100%;
  margin: 0;
  padding: 0;
}

*,
*::before,
*::after {
  box-sizing: border-box;
}

#app {
  /* flex 子项默认 min-width:auto，会导致内部宽内容撑破父级；显式归零修复 */
  min-width: 0;
}

body {
  /* 长串英文/URL 不换行时会撑破容器，强制断行 */
  overflow-wrap: break-word;
}

img,
svg,
video,
canvas {
  max-width: 100%;
}
</style>
