import { createRouter, createWebHistory } from 'vue-router'

// 路由规划：用户端 / 客服工作台 / 管理后台 / Agent 编排
const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'portal',
      component: () => import('../views/portal/Index.vue'),
    },
    {
      path: '/workbench',
      name: 'workbench',
      component: () => import('../views/workbench/Index.vue'),
    },
    // TODO(M2): 管理后台 /admin
    // TODO(M4): Agent 编排 /studio
  ],
})

export default router
