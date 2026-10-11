<template>
  <!-- M1 用户端骨架页：验证请求封装与 SSE 流式渲染链路 -->
  <n-card title="灵犀智能客服（骨架验证页）" style="max-width: 720px; margin: 40px auto">
    <n-space vertical>
      <n-input
        v-model:value="prompt"
        placeholder="输入问题，验证 LLM 网关链路"
        @keyup.enter="send"
      />
      <n-button type="primary" :loading="loading" @click="send">发送</n-button>
      <n-divider />
      <pre class="answer">{{ answer }}</pre>
    </n-space>
  </n-card>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { NCard, NInput, NButton, NSpace, NDivider } from 'naive-ui'
import { streamChat } from '../../api/sse'

const prompt = ref('')
const answer = ref('')
const loading = ref(false)

// M1 直连 AI 域验证接口；M3 接入完整会话链路后替换为 /api/v1/chat 流式接口
async function send() {
  if (!prompt.value || loading.value) return
  loading.value = true
  answer.value = ''
  try {
    await streamChat('http://localhost:8082/api/v1/generate', { prompt: prompt.value }, {
      onChunk: (text) => {
        // 该接口暂为 JSON 一次性返回，此处演示 chunk 渲染；SSE 化在 M3 实现
        answer.value += text
      },
    })
  } catch (err) {
    answer.value = `调用失败: ${err}`
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.answer {
  white-space: pre-wrap;
  word-break: break-word;
  min-height: 120px;
}
</style>
