<template>
  <!-- 403 无权限页：简洁风格 + 返回按钮 -->
  <div class="forbidden">
    <div class="card">
      <svg viewBox="0 0 24 24" width="48" height="48" fill="none" stroke="currentColor">
        <circle cx="12" cy="12" r="9" stroke-width="1.6" />
        <path d="M12 8v5" stroke-width="1.8" stroke-linecap="round" />
        <path d="M12 16h.01" stroke-width="2" stroke-linecap="round" />
      </svg>
      <h1>403</h1>
      <p>抱歉，你没有访问该页面的权限</p>
      <n-button type="primary" @click="goBack">返回</n-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { NButton } from 'naive-ui'
import { homePathForRole } from '../../config/menu'
import { useAuthStore } from '../../stores/auth'

const router = useRouter()
const auth = useAuthStore()

/** 返回：已登录则回落到角色首页，否则回登录页。 */
function goBack(): void {
  if (auth.isLoggedIn) {
    void router.replace(homePathForRole(auth.role))
  } else {
    void router.replace('/login')
  }
}
</script>

<style scoped>
.forbidden {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100vh;
  background: #f5f6f8;
  color: #46709c;
}

.card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  text-align: center;
}

h1 {
  margin: 4px 0 0;
  font-size: 40px;
  color: #1f2733;
}

p {
  margin: 0 0 12px;
  color: #667085;
}
</style>
