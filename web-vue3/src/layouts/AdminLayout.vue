<template>
  <!-- 后台框架：左侧暗色导航 + 顶栏用户模块 + 浅色内容区（保证既有页面可读） -->
  <n-layout has-sider class="admin-layout" style="height: 100vh">
    <!-- 桌面 / 平板侧边栏：窄于 1024px 由脚本收起为 64px 图标栏（Naive 2.40 无 breakpoint 属性，故用 matchMedia 驱动） -->
    <n-layout-sider
      v-if="!isMobile"
      class="admin-sider"
      :class="{ 'is-collapsed': siderCollapsed }"
      :width="214"
      :collapsed-width="64"
      collapse-mode="width"
      :native-scrollbar="false"
      :collapsed="siderCollapsed"
      @update:collapsed="onSiderCollapse"
    >
      <div class="brand">
        <svg viewBox="0 0 48 48" width="26" height="26" aria-hidden="true">
          <defs>
            <linearGradient id="layoutLogo" x1="0" y1="0" x2="1" y2="1">
              <stop offset="0%" stop-color="#4f9dff" />
              <stop offset="100%" stop-color="#a855f7" />
            </linearGradient>
          </defs>
          <path
            d="M24 3 42 13.5v21L24 45 6 34.5v-21z"
            fill="none"
            stroke="url(#layoutLogo)"
            stroke-width="2"
          />
          <circle cx="24" cy="24" r="5.5" fill="url(#layoutLogo)" />
        </svg>
        <span class="brand-name">智能平台</span>
      </div>
      <n-menu
        :value="activePath"
        :options="menuOptions"
        :collapsed="siderCollapsed"
        :collapsed-width="64"
        :collapsed-icon-size="20"
        :theme-overrides="menuTheme"
        :root-indent="18"
        @update:value="onMenuSelect"
      />
    </n-layout-sider>

    <n-layout class="admin-main">
      <n-layout-header class="topbar">
        <div class="topbar-left">
          <!-- 手机端（<768px）打开抽屉菜单；平板端（768~1023）手动展开侧栏 -->
          <n-button
            v-if="compact"
            quaternary
            circle
            size="small"
            class="menu-toggle"
            aria-label="展开菜单"
            @click="toggleMenu"
          >
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor">
              <path d="M4 7h16M4 12h16M4 17h16" stroke-width="1.8" stroke-linecap="round" />
            </svg>
          </n-button>
          <span class="page-title">{{ pageTitle }}</span>
        </div>

        <div class="topbar-right">
          <!-- 显式退出按钮：下拉菜单之外的第二入口，避免依赖下拉点击（更易用、更易自动化验证） -->
          <n-tooltip trigger="hover">
            <template #trigger>
              <n-button quaternary circle size="small" aria-label="退出登录" @click="doLogout">
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor">
                  <path d="M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3" stroke-width="1.8" />
                  <path d="M10 8l-4 4 4 4M6 12h9" stroke-width="1.8" stroke-linecap="round" />
                </svg>
              </n-button>
            </template>
            退出登录
          </n-tooltip>
          <n-dropdown :options="userOptions" trigger="click" @select="onUserSelect">
            <div class="user-chip">
              <n-avatar round :size="30" class="avatar">{{ initial }}</n-avatar>
              <div class="user-meta">
                <span class="user-name">{{ auth.displayName || '未登录' }}</span>
                <span class="user-role">{{ roleLabel }}</span>
              </div>
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor">
                <path d="M6 9l6 6 6-6" stroke-width="1.8" stroke-linecap="round" />
              </svg>
            </div>
          </n-dropdown>
        </div>
      </n-layout-header>

      <n-layout-content class="content" :native-scrollbar="true">
        <!-- 内容区统一内边距随断点收窄；全宽页（工作台/编排）由 is-full-bleed 归零，改用页面自身 padding；
             铺满页（智能会话）由 is-fill-height 提供剩余高度，页面自身纵向撑满 -->
        <div
          class="content-inner"
          :class="{ 'is-full-bleed': isFullBleed, 'is-fill-height': isFillHeight }"
        >
          <router-view />
        </div>
      </n-layout-content>
    </n-layout>
  </n-layout>

  <!-- 手机端（<768px）抽屉：承载与侧栏一致的品牌 + 菜单 -->
  <n-drawer
    v-model:show="drawerVisible"
    placement="left"
    :width="214"
    :content-style="drawerContentStyle"
  >
    <div class="drawer-brand">
      <svg viewBox="0 0 48 48" width="26" height="26" aria-hidden="true">
        <defs>
          <linearGradient id="drawerLogo" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stop-color="#4f9dff" />
            <stop offset="100%" stop-color="#a855f7" />
          </linearGradient>
        </defs>
        <path
          d="M24 3 42 13.5v21L24 45 6 34.5v-21z"
          fill="none"
          stroke="url(#drawerLogo)"
          stroke-width="2"
        />
        <circle cx="24" cy="24" r="5.5" fill="url(#drawerLogo)" />
      </svg>
      <span>智能平台</span>
    </div>
    <n-menu
      :value="activePath"
      :options="menuOptions"
      :theme-overrides="menuTheme"
      :root-indent="18"
      @update:value="onMenuSelect"
    />
  </n-drawer>

  <n-modal v-model:show="profileVisible" preset="card" title="个人信息" style="width: min(440px, 92vw)">
    <n-descriptions :column="1" label-placement="left" bordered size="small">
      <n-descriptions-item label="用户名">{{ auth.user?.username }}</n-descriptions-item>
      <n-descriptions-item label="展示名">{{ auth.user?.displayName }}</n-descriptions-item>
      <n-descriptions-item label="角色">{{ roleLabel }}</n-descriptions-item>
      <n-descriptions-item label="租户">{{ auth.tenantId }}</n-descriptions-item>
      <n-descriptions-item label="状态">{{ auth.user?.status }}</n-descriptions-item>
      <n-descriptions-item label="最近登录">
        {{ formatTime(auth.user?.lastLoginAtMs ?? 0) }}
      </n-descriptions-item>
    </n-descriptions>
  </n-modal>
</template>

<script setup lang="ts">
import { computed, h, onMounted, onUnmounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import {
  NAvatar,
  NButton,
  NDescriptions,
  NDescriptionsItem,
  NDrawer,
  NDropdown,
  NLayout,
  NLayoutContent,
  NLayoutHeader,
  NLayoutSider,
  NMenu,
  NModal,
  NTooltip,
  type DropdownOption,
  type GlobalThemeOverrides,
  type MenuOption,
} from 'naive-ui'
import MenuIcon from '../components/MenuIcon.vue'
import { MENU_ITEMS, ROLE_LABELS } from '../config/menu'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const profileVisible = ref(false)

/** 侧栏收起态（<1024px 自动置 true，平板下可手动展开）。 */
const siderCollapsed = ref(false)
/** 手机端抽屉显隐。 */
const drawerVisible = ref(false)
/** 视口断点：isTablet = 768~1023，isMobile = <768。 */
const isTablet = ref(false)
const isMobile = ref(false)
/** compact 覆盖平板与手机（<1024px），用于顶栏汉堡按钮显隐。 */
const compact = computed(() => isTablet.value || isMobile.value)

/** 抽屉暗色背景与零内边距（复用侧栏配色）。 */
const drawerContentStyle = {
  background: 'linear-gradient(180deg, #0b1020 0%, #0e1330 100%)',
  padding: '0',
}

let mqTablet: MediaQueryList | null = null
let mqMobile: MediaQueryList | null = null

/** 同步视口断点：窄屏自动收起侧栏，回到宽屏恢复展开并关闭抽屉。 */
function syncViewport(): void {
  isTablet.value = mqTablet?.matches ?? false
  isMobile.value = mqMobile?.matches ?? false
  siderCollapsed.value = compact.value
  if (!isMobile.value) {
    drawerVisible.value = false
  }
}

onMounted(() => {
  mqTablet = window.matchMedia('(max-width: 1023px)')
  mqMobile = window.matchMedia('(max-width: 767px)')
  syncViewport()
  mqTablet.addEventListener('change', syncViewport)
  mqMobile.addEventListener('change', syncViewport)
})

onUnmounted(() => {
  mqTablet?.removeEventListener('change', syncViewport)
  mqMobile?.removeEventListener('change', syncViewport)
})

/** 全宽页（工作台 / 编排）：内容区不额外加内边距，避免与页面自身布局冲突。 */
const isFullBleed = computed(() => route.name === 'workbench' || route.name === 'studio')

/** 铺满页（智能会话 / 运营报表 / 知识库）：内容区撑满「视口减去顶栏」的剩余高度，页面内部自行吃掉剩余空间。 */
const isFillHeight = computed(
  () => route.name === 'portal' || route.name === 'report' || route.name === 'kb',
)

/** 当前高亮菜单项（以路由路径为键）。 */
const activePath = computed(() => route.path)
const pageTitle = computed(() => route.meta.title ?? '')
const roleLabel = computed(() => ROLE_LABELS[auth.role] ?? auth.role ?? '')
const initial = computed(() => (auth.displayName || 'U').slice(0, 1).toUpperCase())

/** 按当前角色过滤菜单项（admin 恒可见全部）。 */
const menuOptions = computed<MenuOption[]>(() =>
  MENU_ITEMS.filter((item) => auth.hasRole(...item.roles)).map((item) => ({
    // 标签用 RouterLink 渲染：点击即为真实路由跳转（比依赖菜单选中事件更可靠，
    // 同时天然支持新标签页打开等浏览器原生行为）
    label: () => h(RouterLink, { to: item.path }, { default: () => item.title }),
    key: item.path,
    icon: () => h(MenuIcon, { name: item.key }),
  })),
)

const userOptions: DropdownOption[] = [
  { label: '个人信息', key: 'profile' },
  { label: '退出登录', key: 'logout' },
]

/** 暗色导航配色：与登录页同色系。 */
const menuTheme: GlobalThemeOverrides['Menu'] = {
  itemColorActive: 'rgba(79, 157, 255, 0.18)',
  itemColorActiveHover: 'rgba(79, 157, 255, 0.26)',
  itemColorHover: 'rgba(255, 255, 255, 0.06)',
  itemTextColor: 'rgba(226, 236, 255, 0.72)',
  itemTextColorHover: '#ffffff',
  itemTextColorActive: '#ffffff',
  itemIconColor: 'rgba(226, 236, 255, 0.6)',
  itemIconColorHover: '#ffffff',
  itemIconColorActive: '#5aa2ff',
  fontWeightActive: '600',
}

/** 汉堡按钮：手机端切换抽屉，平板端切换侧栏展开/收起。 */
function toggleMenu(): void {
  if (isMobile.value) {
    drawerVisible.value = !drawerVisible.value
    return
  }
  siderCollapsed.value = !siderCollapsed.value
}

/** 侧栏收起状态由组件内部触发（用户点击触发器等）时同步到本地状态。 */
function onSiderCollapse(value: boolean): void {
  siderCollapsed.value = value
}

function onMenuSelect(key: string): void {
  drawerVisible.value = false
  if (key !== route.path) {
    void router.push(key)
  }
}

async function onUserSelect(key: string): Promise<void> {
  if (key === 'profile') {
    profileVisible.value = true
    return
  }
  if (key === 'logout') {
    await doLogout()
  }
}

/** 退出登录：先清本地登录态，再跳登录页（顺序不可颠倒，否则守卫会按「已登录」把请求弹回首页）。 */
async function doLogout(): Promise<void> {
  await auth.logout()
  void router.replace('/login')
}

function formatTime(epochMs: number): string {
  return epochMs ? new Date(epochMs).toLocaleString() : '—'
}
</script>

<style scoped>
.admin-layout,
.admin-main {
  min-width: 0;
}

.admin-sider {
  background: linear-gradient(180deg, #0b1020 0%, #0e1330 100%);
}

.admin-sider :deep(.n-layout-sider-scroll-container) {
  background: transparent;
}

/* 收起为图标栏时：品牌只留 logo，菜单只留图标 */
.admin-sider.is-collapsed .brand {
  justify-content: center;
  padding: 0;
}

.admin-sider.is-collapsed .brand-name {
  display: none;
}

.brand,
.drawer-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 56px;
  padding: 0 18px;
  color: #eaf2ff;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 1px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  height: 56px;
  padding: 0 20px;
  background: #ffffff;
  border-bottom: 1px solid #eef1f5;
}

.topbar-left {
  display: flex;
  align-items: center;
  gap: 10px;
  /* 允许标题在窄屏被截断而不是撑破顶栏 */
  min-width: 0;
}

.menu-toggle {
  flex: 0 0 auto;
}

.page-title {
  font-size: 16px;
  font-weight: 600;
  color: #1f2733;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 0 0 auto;
}

.user-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 10px 4px 4px;
  border-radius: 999px;
  cursor: pointer;
  color: #667085;
  transition: background 0.15s;
}

.user-chip:hover {
  background: #f2f5fa;
}

.avatar {
  background: linear-gradient(135deg, #4f9dff, #a855f7);
  color: #fff;
  font-weight: 600;
}

.user-meta {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
  min-width: 0;
}

.user-name {
  font-size: 13px;
  font-weight: 600;
  color: #1f2733;
}

.user-role {
  font-size: 11px;
  color: #98a2b3;
}

.content {
  background: #f5f6f8;
  min-width: 0;
}

/* 内容区统一内边距，随断点由 24px 收窄到 10px */
.content-inner {
  padding: 24px;
  min-width: 0;
}

.content-inner.is-full-bleed {
  padding: 0;
}

/*
 * 铺满页（智能会话）：撑满「视口 − 顶栏 56px」的剩余高度，并作为纵向 flex 容器，
 * 使页面根节点可用 flex:1 吃掉剩余空间。
 *
 * **必须是确定高度（height）而非 min-height**：只有确定高度才能让子项的 flex:1 稳定分配剩余空间；
 * 用 min-height 时子项是否被拉伸取决于引擎对「内容尺寸 vs min-height」的取值，会出现
 * 「有左列时铺满、无左列时不铺满」这类不稳定表现（实测踩到）。
 * 全局 box-sizing: border-box，故该高度已含各断点内边距，无需按断点重算。
 */
.content-inner.is-fill-height {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 56px);
}

@media (max-width: 1279px) {
  .content-inner {
    padding: 16px;
  }
}

@media (max-width: 1023px) {
  .content-inner {
    padding: 12px;
  }
}

@media (max-width: 767px) {
  .content-inner {
    padding: 10px;
  }
}

/* 小屏顶栏：收窄内边距、隐藏次要信息（角色标签），用户名保留可截断 */
@media (max-width: 1023px) {
  .topbar {
    padding: 0 12px;
  }

  .user-role {
    display: none;
  }
}

@media (max-width: 767px) {
  .topbar {
    padding: 0 8px;
  }

  .user-name {
    max-width: 96px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}
</style>
