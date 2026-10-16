import { createRouter, createWebHistory } from 'vue-router'
import AdminLayout from '../layouts/AdminLayout.vue'
import { homePathForRole } from '../config/menu'
import { useAuthStore } from '../stores/auth'

// 路由 meta 扩展：标题与可见角色（角色判断见全局前置守卫）
declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    roles?: string[]
  }
}

// 路由规划：登录 / 403 / 后台框架（用户端 · 工作台 · 报表 · 编排 · 日志 · 用户）
const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      // 登录页独立于后台框架
      path: '/login',
      name: 'login',
      component: () => import('../views/login/Index.vue'),
      meta: { title: '登录' },
    },
    {
      path: '/403',
      name: 'forbidden',
      component: () => import('../views/error/Forbidden.vue'),
      meta: { title: '无权限' },
    },
    {
      path: '/',
      component: AdminLayout,
      redirect: '/portal',
      children: [
        {
          path: 'portal',
          name: 'portal',
          component: () => import('../views/portal/Index.vue'),
          meta: { title: '智能会话', roles: ['admin', 'agent', 'user'] },
        },
        {
          path: 'workbench',
          name: 'workbench',
          component: () => import('../views/workbench/Index.vue'),
          meta: { title: '客服工作台', roles: ['admin', 'agent'] },
        },
        {
          path: 'report',
          name: 'report',
          component: () => import('../views/report/Index.vue'),
          meta: { title: '运营报表', roles: ['admin', 'agent'] },
        },
        {
          path: 'studio',
          name: 'studio',
          component: () => import('../views/studio/Index.vue'),
          meta: { title: '编排 Studio', roles: ['admin'] },
        },
        {
          path: 'kb',
          name: 'kb',
          component: () => import('../views/kb/Index.vue'),
          meta: { title: '知识库', roles: ['admin'] },
        },
        {
          path: 'logs',
          name: 'logs',
          component: () => import('../views/logs/Index.vue'),
          meta: { title: '系统日志', roles: ['admin'] },
        },
        {
          path: 'users',
          name: 'users',
          component: () => import('../views/users/Index.vue'),
          meta: { title: '用户管理', roles: ['admin'] },
        },
      ],
    },
    // 未匹配路径统一回落首页
    { path: '/:pathMatch(.*)*', redirect: '/portal' },
  ],
})

/**
 * 全局前置守卫：
 * - 未登录（无 token）→ 携带 redirect 跳登录页；
 * - 已登录但角色不在 meta.roles → 跳 403；
 * - 已登录访问 /login → 回角色首页；
 * - 有 token 但尚无 user → 先拉取身份（失败即视为登录失效，回登录页）。
 */
router.beforeEach(async (to) => {
  const auth = useAuthStore()
  const loggedIn = auth.isLoggedIn

  if (to.path === '/login') {
    return loggedIn ? { path: homePathForRole(auth.role) } : true
  }

  if (!loggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  // 刷新页面后仅有 token、user 为空：先补拉身份再校验角色
  if (!auth.user) {
    try {
      await auth.loadMe()
    } catch {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
  }

  const roles = to.meta.roles
  if (roles && roles.length > 0 && !auth.hasRole(...roles)) {
    return { path: '/403' }
  }
  return true
})

export default router
