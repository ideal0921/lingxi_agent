import http from './http'
import { unwrapResult } from './result'

/**
 * 认证 API：对接额外新增的 /api/v1/auth/* 管理类接口。
 * 响应为 Result 信封，统一经 unwrapResult 解包。
 */

/** 用户视图（登录态 / 用户管理共用）。 */
export interface UserView {
  userId: string
  tenantId: string
  username: string
  displayName: string
  /** admin | agent | user */
  role: string
  /** ENABLED | DISABLED */
  status: string
  createdAtMs: number
  lastLoginAtMs: number
}

/** 登录结果。 */
export interface LoginResult {
  token: string
  tokenType: string
  expiresInSeconds: number
  user: UserView
}

/** 登录参数。 */
export interface LoginPayload {
  username: string
  password: string
  /** 租户标识，缺省由服务端按默认租户处理 */
  tenantId?: string
}

/** 登录（免鉴权接口）。 */
export async function login(payload: LoginPayload): Promise<LoginResult> {
  const body = await http.post('/auth/login', payload)
  return unwrapResult<LoginResult>(body)
}

/** 查询当前登录身份（需令牌）。 */
export async function fetchMe(): Promise<UserView> {
  const body = await http.get('/auth/me')
  return unwrapResult<UserView>(body)
}

/** 登出（服务端失效令牌）。 */
export async function logout(): Promise<void> {
  const body = await http.post('/auth/logout')
  unwrapResult<unknown>(body)
}
