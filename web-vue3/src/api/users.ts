import http from './http'
import { unwrapResult, type PageResult } from './result'
import type { UserView } from './auth'

/**
 * 用户管理 API：对接 /api/v1/users*（需 admin）。
 * 响应为 Result 信封，统一经 unwrapResult 解包。
 */

/** 用户状态取值。 */
export type UserStatus = 'ENABLED' | 'DISABLED'

/** 用户列表查询参数。 */
export interface UserQuery {
  keyword?: string
  page?: number
  size?: number
}

/** 分页查询用户。 */
export async function listUsers(query: UserQuery = {}): Promise<PageResult<UserView>> {
  const body = await http.get('/users', { params: query })
  return unwrapResult<PageResult<UserView>>(body)
}

/** 新建用户。 */
export async function createUser(payload: {
  username: string
  password: string
  displayName: string
  role: string
}): Promise<UserView> {
  const body = await http.post('/users', payload)
  return unwrapResult<UserView>(body)
}

/** 重置指定用户口令。 */
export async function resetPassword(userId: string, password: string): Promise<void> {
  const body = await http.post(`/users/${userId}/password`, { password })
  unwrapResult<unknown>(body)
}

/** 启用 / 禁用用户。 */
export async function updateUserStatus(userId: string, status: UserStatus): Promise<UserView> {
  const body = await http.post(`/users/${userId}/status`, { status })
  return unwrapResult<UserView>(body)
}
