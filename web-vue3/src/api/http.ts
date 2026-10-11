import axios from 'axios'

// 统一 HTTP 客户端：走 Vite 代理 /api → Go 网关
const http = axios.create({
  baseURL: '/api/v1',
  timeout: 10_000,
})

// 响应拦截：统一解包 Result 结构，非 0 code 视为业务失败
http.interceptors.response.use(
  (resp) => resp.data,
  (error) => {
    // TODO(M2): 401 时跳转登录
    return Promise.reject(error)
  },
)

export default http
