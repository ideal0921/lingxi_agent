import axios from 'axios'
import { createDiscreteApi } from 'naive-ui'

/** 本地令牌键：与 auth store 保持一致（此处直读 localStorage 以避免循环依赖）。 */
export const TOKEN_KEY = 'lingxi_token'

// 全局消息提示：拦截器不在组件上下文中，使用 naive-ui 离散 API 弹出提示。
const { message } = createDiscreteApi(['message'])

// 统一 HTTP 客户端：走 Vite 代理 /api → Go 网关
const http = axios.create({
  baseURL: '/api/v1',
  timeout: 10_000,
})

// 请求拦截：自动附加 Bearer 令牌。
// 这里直读 localStorage 而非引用 store——store → api → http 会形成循环依赖。
http.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 响应拦截：解包 axios 响应为「响应体本身」，并统一处理鉴权异常。
http.interceptors.response.use(
  (resp) => resp.data,
  (error) => {
    const status: number | undefined = error?.response?.status
    const url: string = error?.config?.url ?? ''
    // 登录接口用 HTTP 401 表达「账号或密码错误」，需保留给调用方展示，故不触发跳转。
    const isLoginRequest = url.includes('/auth/login')
    if (status === 401 && !isLoginRequest) {
      // 令牌失效：清除本地令牌并携带当前路径跳转登录页，登录后可回跳。
      localStorage.removeItem(TOKEN_KEY)
      if (location.pathname !== '/login') {
        const redirect = encodeURIComponent(location.pathname + location.search)
        location.assign(`/login?redirect=${redirect}`)
      }
    } else if (status === 403) {
      message.error('无权限')
    }
    return Promise.reject(error)
  },
)

export default http
