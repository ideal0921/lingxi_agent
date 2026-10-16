<template>
  <!-- 外太空登录页：暗色渐变背景 + 深空星野/行星/飞船/跃迁光带等纯 CSS/SVG 场景 -->
  <div class="login-page">
    <!-- 背景氛围层（装饰，不接收事件） -->
    <div class="fx-grid"></div>
    <div class="fx-glow glow-a"></div>
    <div class="fx-glow glow-b"></div>

    <!-- 太空场景装饰层：pointer-events:none 且 z-index 低于表单，保证表单始终可交互；
         state 驱动场景联动（提交中跃迁充能 / 成功跃迁爆发 / 失败告警抖动） -->
    <SpaceScene :state="sceneState" />

    <!-- 原有的细扫描线（与光带叠加，保持既有视觉） -->
    <div class="fx-scan"></div>

    <n-config-provider :theme="darkTheme" :theme-overrides="loginTheme">
      <div class="login-card">
        <!-- 内联 SVG 标识：图形 + 文字排版 -->
        <div class="brand">
          <svg class="brand-logo" viewBox="0 0 48 48" width="44" height="44" aria-hidden="true">
            <defs>
              <linearGradient id="loginLogo" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0%" stop-color="#4f9dff" />
                <stop offset="100%" stop-color="#a855f7" />
              </linearGradient>
            </defs>
            <path
              d="M24 3 42 13.5v21L24 45 6 34.5v-21z"
              fill="none"
              stroke="url(#loginLogo)"
              stroke-width="2"
            />
            <circle cx="24" cy="24" r="5.5" fill="url(#loginLogo)" />
            <path
              d="M24 7v7M24 34v7M10.5 17l6 3.5M31.5 27l6 3.5M37.5 17l-6 3.5M16.5 27l-6 3.5"
              stroke="url(#loginLogo)"
              stroke-width="1.3"
            />
          </svg>
          <span class="brand-name">智能平台</span>
        </div>
        <p class="subtitle">LINGXI INTELLIGENCE PLATFORM</p>

        <div class="form">
          <n-input
            v-model:value="username"
            placeholder="用户名"
            size="large"
            :input-props="{ autocomplete: 'username' }"
            @keyup.enter="submit"
          />
          <n-input
            v-model:value="password"
            type="password"
            show-password-on="click"
            placeholder="密码"
            size="large"
            :input-props="{ autocomplete: 'current-password' }"
            @keyup.enter="submit"
          />

          <!-- 高级选项：租户默认 lingxi，折叠以保持界面简洁 -->
          <div class="advanced">
            <button type="button" class="advanced-toggle" @click="advanced = !advanced">
              {{ advanced ? '收起高级选项' : '高级选项' }}
            </button>
            <n-input
              v-show="advanced"
              v-model:value="tenantId"
              placeholder="租户标识"
              size="large"
            />
          </div>

          <n-alert v-if="error" type="error" :show-icon="false" class="error">
            {{ error }}
          </n-alert>

          <n-button
            type="primary"
            size="large"
            block
            :loading="submitting"
            class="submit"
            @click="submit"
          >
            登 录
          </n-button>
        </div>
      </div>
    </n-config-provider>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  NAlert,
  NButton,
  NConfigProvider,
  NInput,
  darkTheme,
  type GlobalThemeOverrides,
} from 'naive-ui'
import { homePathForRole } from '../../config/menu'
import { useAuthStore } from '../../stores/auth'
import SpaceScene from './SpaceScene.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const username = ref('')
const password = ref('')
const tenantId = ref('lingxi')
const advanced = ref(false)
const submitting = ref(false)
const error = ref('')

/** 场景联动状态：驱动太空场景的跃迁充能 / 跃迁爆发 / 告警抖动。 */
const sceneState = ref<'idle' | 'submitting' | 'success' | 'error'>('idle')
/** 跃迁爆发可见时长（ms）：仅影响观感，不阻塞登录链路。 */
const SCENE_BURST_MS = 240
/** 告警抖动后复位为常态的延时（ms）：须 ≥ 抖动动画时长（0.46s）。 */
const SCENE_IDLE_MS = 900

/** 场景定时器：同一时刻只会有一个（等待爆发或复位），故共用一个句柄。 */
let sceneTimer: ReturnType<typeof setTimeout> | undefined

/** 延时后把场景复位为 idle，避免状态类常驻导致后续动画不再触发。 */
function scheduleSceneIdle(delayMs: number): void {
  if (sceneTimer) clearTimeout(sceneTimer)
  sceneTimer = setTimeout(() => {
    sceneState.value = 'idle'
  }, delayMs)
}

/** 等待跃迁爆发动画播完，让「登录成功」在跳转前被看见。 */
function waitSceneBurst(): Promise<void> {
  return new Promise((resolve) => {
    if (sceneTimer) clearTimeout(sceneTimer)
    sceneTimer = setTimeout(resolve, SCENE_BURST_MS)
  })
}

onBeforeUnmount(() => {
  if (sceneTimer) clearTimeout(sceneTimer)
})

/** 登录页局部暗色主题：仅覆盖登录页，不影响全局浅色主题与既有页面。 */
const loginTheme: GlobalThemeOverrides = {
  common: {
    primaryColor: '#4f9dff',
    primaryColorHover: '#6fb0ff',
    primaryColorPressed: '#3b82f6',
  },
  Input: {
    color: 'rgba(255, 255, 255, 0.06)',
    colorFocus: 'rgba(255, 255, 255, 0.1)',
    border: '1px solid rgba(255, 255, 255, 0.16)',
    borderFocus: '1px solid #4f9dff',
    borderHover: '1px solid rgba(255, 255, 255, 0.3)',
    textColor: '#eaf2ff',
    placeholderColor: 'rgba(255, 255, 255, 0.4)',
    caretColor: '#4f9dff',
  },
}

async function submit(): Promise<void> {
  error.value = ''
  const name = username.value.trim()
  if (!name || !password.value) {
    error.value = '请输入用户名与密码'
    sceneState.value = 'error' // 校验失败也给出场景级反馈，避免只有文案变化
    scheduleSceneIdle(SCENE_IDLE_MS)
    return
  }
  submitting.value = true
  sceneState.value = 'submitting' // 跃迁充能：星野与光条整体加速
  try {
    await auth.login(name, password.value, tenantId.value.trim() || undefined)
    sceneState.value = 'success' // 跃迁爆发（含一次性爆闪）
    await waitSceneBurst()
    // 优先回跳 redirect；否则按角色落到首个可用菜单
    const redirect = route.query.redirect
    const target = typeof redirect === 'string' && redirect ? redirect : homePathForRole(auth.role)
    await router.replace(target)
  } catch (e) {
    error.value = (e as Error).message || '登录失败，请稍后重试'
    sceneState.value = 'error'
    scheduleSceneIdle(SCENE_IDLE_MS)
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.login-page {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  min-height: 100dvh;
  padding: 24px 0;
  overflow: hidden;
  background:
    radial-gradient(1200px 600px at 15% 10%, #1a2350 0%, transparent 60%),
    radial-gradient(900px 500px at 85% 90%, #3a1d5e 0%, transparent 55%),
    linear-gradient(135deg, #070a18 0%, #0d1330 50%, #161038 100%);
}

/* 网格：整体平移制造缓慢流动感，遮罩让边缘淡出 */
.fx-grid {
  position: absolute;
  /* 只外扩一个网格单元（46px）：漂移幅度就是一个单元，够用且图层面积比 -50%（4 倍视口）
     小得多（栅格内存显著降低），mask 位置与观感基本不变 */
  inset: -46px;
  background-image:
    linear-gradient(rgba(90, 162, 255, 0.08) 1px, transparent 1px),
    linear-gradient(90deg, rgba(90, 162, 255, 0.08) 1px, transparent 1px);
  background-size: 46px 46px;
  mask-image: radial-gradient(circle at 50% 45%, #000 0%, transparent 72%);
  -webkit-mask-image: radial-gradient(circle at 50% 45%, #000 0%, transparent 72%);
  animation: grid-drift 26s linear infinite;
}

@keyframes grid-drift {
  to {
    transform: translate3d(46px, 46px, 0);
  }
}

/* 光斑：软渐变弥散光缓慢浮动（仅 transform 动画，无 filter，避免每帧重算模糊） */
.fx-glow {
  position: absolute;
  border-radius: 50%;
  opacity: 0.5;
  /* 去掉 filter:blur(70px)：460–520px 的光斑每帧随位移重算模糊，代价高；
     改为多段软渐变模拟弥散（半径按原模糊半径外扩 ~70px 以保持观感一致）。
     同时去掉 will-change：该元素本就有无限动画会被自动提升，显式声明只是重复占层。 */
}

.glow-a {
  width: 600px;
  height: 600px;
  top: -190px;
  left: -150px;
  background: radial-gradient(
    circle,
    rgba(59, 130, 246, 0.4) 0%,
    rgba(59, 130, 246, 0.24) 24%,
    rgba(59, 130, 246, 0.1) 50%,
    rgba(59, 130, 246, 0.03) 73%,
    transparent 100%
  );
  animation: float-a 18s ease-in-out infinite alternate;
}

.glow-b {
  width: 660px;
  height: 660px;
  bottom: -230px;
  right: -170px;
  background: radial-gradient(
    circle,
    rgba(168, 85, 247, 0.36) 0%,
    rgba(168, 85, 247, 0.22) 24%,
    rgba(168, 85, 247, 0.09) 50%,
    rgba(168, 85, 247, 0.03) 73%,
    transparent 100%
  );
  animation: float-b 22s ease-in-out infinite alternate;
}

@keyframes float-a {
  to {
    transform: translate3d(80px, 60px, 0) scale(1.15);
  }
}

@keyframes float-b {
  to {
    transform: translate3d(-70px, -50px, 0) scale(1.1);
  }
}

/* 细扫描线：自上而下缓慢划过 */
.fx-scan {
  position: absolute;
  left: 0;
  right: 0;
  height: 2px;
  background: linear-gradient(90deg, transparent, rgba(90, 162, 255, 0.65), transparent);
  opacity: 0.35;
  animation: scan 9s linear infinite;
}

@keyframes scan {
  0% {
    transform: translateY(0);
  }
  100% {
    transform: translateY(100vh);
  }
}

/* 玻璃拟态卡片：z-index 高于装饰层，始终可交互 */
.login-card {
  position: relative;
  z-index: 2;
  width: min(380px, 92vw);
  padding: 40px 34px 34px;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.14);
  box-shadow:
    0 20px 60px rgba(0, 0, 0, 0.45),
    inset 0 1px 0 rgba(255, 255, 255, 0.08);
  backdrop-filter: blur(18px);
  -webkit-backdrop-filter: blur(18px);
}

.brand {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
}

.brand-name {
  font-size: 26px;
  font-weight: 700;
  letter-spacing: 4px;
  background: linear-gradient(90deg, #cfe3ff, #a99bff);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.subtitle {
  margin: 8px 0 26px;
  text-align: center;
  font-size: 11px;
  letter-spacing: 2px;
  color: rgba(226, 236, 255, 0.42);
}

.form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.advanced {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.advanced-toggle {
  align-self: flex-start;
  padding: 0;
  background: none;
  border: none;
  font-size: 12px;
  color: rgba(226, 236, 255, 0.55);
  cursor: pointer;
}

.advanced-toggle:hover {
  color: #5aa2ff;
}

.submit {
  margin-top: 6px;
  font-weight: 600;
  letter-spacing: 4px;
}

@media (max-width: 767px) {
  .login-card {
    padding: 28px 22px 24px;
  }

  .brand-name {
    font-size: 22px;
    letter-spacing: 2px;
  }
}

/* 无障碍：偏好减少动效时关闭基础装饰动画（场景层在 SpaceScene 内单独处理） */
@media (prefers-reduced-motion: reduce) {
  .fx-grid,
  .fx-glow,
  .fx-scan {
    animation: none !important;
  }

  .fx-scan {
    opacity: 0 !important;
  }
}
</style>
