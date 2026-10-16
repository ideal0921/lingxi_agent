/**
 * 侧边导航菜单配置：图标 key 对应 components/MenuIcon.vue 中同名内联 SVG。
 */

/** 角色类型（后端返回字符串，此处收敛常用取值）。 */
export type Role = 'admin' | 'agent' | 'user'

/** 菜单项配置。 */
export interface MenuItemConfig {
  /** 唯一键，同时作为 MenuIcon 的图形 key */
  key: string
  title: string
  path: string
  /** 可见角色列表 */
  roles: Role[]
}

/** 全部菜单项（按角色过滤后渲染）。 */
export const MENU_ITEMS: MenuItemConfig[] = [
  { key: 'portal', title: '智能会话', path: '/portal', roles: ['admin', 'agent', 'user'] },
  { key: 'workbench', title: '客服工作台', path: '/workbench', roles: ['admin', 'agent'] },
  { key: 'report', title: '运营报表', path: '/report', roles: ['admin', 'agent'] },
  { key: 'studio', title: '编排 Studio', path: '/studio', roles: ['admin'] },
  { key: 'kb', title: '知识库', path: '/kb', roles: ['admin'] },
  { key: 'logs', title: '系统日志', path: '/logs', roles: ['admin'] },
  { key: 'users', title: '用户管理', path: '/users', roles: ['admin'] },
]

/** 角色中文名。 */
export const ROLE_LABELS: Record<string, string> = {
  admin: '管理员',
  agent: '客服',
  user: '用户',
}

/** 各角色缺省首页（均落在该角色可见范围内）。 */
const HOME_BY_ROLE: Record<string, string> = {
  admin: '/report',
  agent: '/workbench',
  user: '/portal',
}

/**
 * 按角色确定首页路径：登录成功与「已登录访问 /login」时回跳用。
 * 未知角色一律回落 /portal（由守卫做角色校验兜底）。
 */
export function homePathForRole(role: string): string {
  return HOME_BY_ROLE[role] ?? '/portal'
}
