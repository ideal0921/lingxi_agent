import { ref } from 'vue'
import {
  getCsatReport,
  getFunnelReport,
  getTokenReport,
  type CsatReport,
  type FunnelReport,
  type TokenReport,
} from '../api/report'
import { useAuthStore } from '../stores/auth'

/** 空数据兜底：报表未返回前渲染空态，避免模板取空对象字段报错。 */
const EMPTY_FUNNEL: FunnelReport = { created_sessions: 0, stages: [] }
const EMPTY_TOKEN: TokenReport = {
  total_requests: 0,
  total_prompt_tokens: 0,
  total_completion_tokens: 0,
  total_estimated_requests: 0,
  rows: [],
}
const EMPTY_CSAT: CsatReport = {
  feedback_count: 0,
  average_rating: 0,
  csat: 0,
  distribution: [],
  recent: [],
}

/**
 * 运营报表状态管理（M5-5.1）：漏斗 / Token 消耗 / 满意度三块报表。
 * 三块报表相互独立，采用并行请求（allSettled）：单块失败不影响其余两块渲染。
 */
export function useReport() {
  // 默认租户取自登录态，避免写死导致跨租户查询
  const tenantId = ref(useAuthStore().tenantId)
  // 日期范围（naive-ui daterange 直接给出 [起, 止] epoch 毫秒；null 表示默认最近 7 天）
  const dateRange = ref<[number, number] | null>(null)
  const loading = ref(false)
  const error = ref('')

  const funnel = ref<FunnelReport>({ ...EMPTY_FUNNEL })
  const token = ref<TokenReport>({ ...EMPTY_TOKEN })
  const csat = ref<CsatReport>({ ...EMPTY_CSAT })

  /** 拉取三块报表（失败块保留上次数据并汇总提示）。 */
  async function load(): Promise<void> {
    const tenant = tenantId.value.trim()
    if (!tenant) {
      error.value = '租户不能为空'
      return
    }
    loading.value = true
    error.value = ''
    const query = { tenantId: tenant, fromMs: dateRange.value?.[0], toMs: dateRange.value?.[1] }
    const [funnelResult, tokenResult, csatResult] = await Promise.allSettled([
      getFunnelReport(query),
      getTokenReport(query),
      getCsatReport(query),
    ])
    if (funnelResult.status === 'fulfilled') funnel.value = funnelResult.value
    if (tokenResult.status === 'fulfilled') token.value = tokenResult.value
    if (csatResult.status === 'fulfilled') csat.value = csatResult.value

    const failed = [funnelResult, tokenResult, csatResult].filter(
      (result) => result.status === 'rejected',
    ).length
    error.value = failed > 0 ? `${failed} 块报表加载失败，请稍后重试` : ''
    loading.value = false
  }

  return { tenantId, dateRange, loading, error, funnel, token, csat, load }
}
