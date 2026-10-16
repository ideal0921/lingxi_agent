import { defineStore } from 'pinia'
import { login as loginApi, logout as logoutApi, fetchMe, type UserView } from '../api/auth'
import { TOKEN_KEY } from '../api/http'

/**
 * 认证状态：令牌持久化于 localStorage，用户信息内存持有（启动时经 loadMe 拉取）。
 */
export const useAuthStore = defineStore('auth', {
  state: () => ({
    // 初始化即从 localStorage 读取，刷新页面可恢复登录态
    token: localStorage.getItem(TOKEN_KEY) ?? '',
    user: null as UserView | null,
  }),

  getters: {
    isLoggedIn: (state) => !!state.token,
    role: (state) => state.user?.role ?? '',
    tenantId: (state) => state.user?.tenantId ?? '',
    /** 展示名优先，回落用户名 */
    displayName: (state) => state.user?.displayName || state.user?.username || '',
  },

  actions: {
    /** 保存令牌（内存 + localStorage）。 */
    setToken(token: string): void {
      this.token = token
      localStorage.setItem(TOKEN_KEY, token)
    },

    /** 清除本地登录态。 */
    clear(): void {
      this.token = ''
      this.user = null
      localStorage.removeItem(TOKEN_KEY)
    },

    /** 登录：成功后保存令牌与用户信息。后端 401 的 message 透传给调用方展示。 */
    async login(username: string, password: string, tenantId?: string): Promise<void> {
      try {
        const result = await loginApi({ username, password, tenantId })
        this.setToken(result.token)
        this.user = result.user
      } catch (error) {
        // 登录失败为 HTTP 401，业务信息在响应体的 message 字段
        const message = (error as { response?: { data?: { message?: string } } })?.response?.data
          ?.message
        throw new Error(message || '登录失败，请稍后重试')
      }
    },

    /** 拉取当前身份：失败即登出（令牌失效或服务不可用）。 */
    async loadMe(): Promise<void> {
      if (!this.token) {
        return
      }
      try {
        this.user = await fetchMe()
      } catch (error) {
        this.clear()
        throw error
      }
    },

    /** 登出：调用后端接口（失败也照常清理本地），再清空本地状态。 */
    async logout(): Promise<void> {
      try {
        if (this.token) {
          await logoutApi()
        }
      } catch {
        // 服务端登出失败不阻塞本地清理
      }
      this.clear()
    },

    /** 角色判断：admin 视为拥有全部权限。 */
    hasRole(...roles: string[]): boolean {
      if (!this.user) {
        return false
      }
      return this.user.role === 'admin' || roles.includes(this.user.role)
    },
  },
})
