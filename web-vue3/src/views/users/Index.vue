<template>
  <!-- 用户管理页：列表 / 搜索 / 分页 / 新建 / 重置口令 / 启用禁用 -->
  <n-space vertical :size="14" class="users-page">
    <n-card size="small" title="用户管理">
      <template #header-extra>
        <n-button size="small" type="primary" @click="openCreate">新建用户</n-button>
      </template>
      <n-space align="center" :size="12" :wrap="true">
        <n-input
          v-model:value="keyword"
          placeholder="搜索用户名 / 展示名"
          clearable
          class="search-input"
          @keyup.enter="onSearch"
        />
        <n-button @click="onSearch">搜索</n-button>
      </n-space>
    </n-card>

    <n-card size="small">
      <n-data-table
        remote
        :columns="columns"
        :data="rows"
        :loading="loading"
        :pagination="pagination"
        :bordered="false"
        :single-line="false"
        :scroll-x="1080"
        size="small"
      />
    </n-card>
  </n-space>

  <!-- 新建用户 -->
  <n-modal v-model:show="createVisible" preset="card" title="新建用户" style="width: min(460px, 92vw)">
    <n-space vertical :size="12">
      <n-input v-model:value="createForm.username" placeholder="用户名" />
      <n-input
        v-model:value="createForm.password"
        type="password"
        show-password-on="click"
        placeholder="初始口令"
      />
      <n-input v-model:value="createForm.displayName" placeholder="展示名" />
      <n-select v-model:value="createForm.role" :options="roleOptions" placeholder="角色" />
      <n-button type="primary" block :loading="submitting" @click="submitCreate">创建</n-button>
    </n-space>
  </n-modal>

  <!-- 重置口令 -->
  <n-modal v-model:show="resetVisible" preset="card" title="重置口令" style="width: min(420px, 92vw)">
    <n-space vertical :size="12">
      <span class="hint">为用户「{{ resetTarget?.displayName || resetTarget?.username }}」设置新口令</span>
      <n-input
        v-model:value="newPassword"
        type="password"
        show-password-on="click"
        placeholder="新口令"
      />
      <n-button type="primary" block :loading="submitting" @click="submitReset">确认重置</n-button>
    </n-space>
  </n-modal>
</template>

<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue'
import {
  NButton,
  NCard,
  NDataTable,
  NInput,
  NModal,
  NSelect,
  NSpace,
  NTag,
  useDialog,
  useMessage,
  type DataTableColumns,
  type PaginationProps,
  type SelectOption,
} from 'naive-ui'
import {
  createUser,
  listUsers,
  resetPassword,
  updateUserStatus,
  type UserStatus,
} from '../../api/users'
import type { UserView } from '../../api/auth'

const message = useMessage()
const dialog = useDialog()

const keyword = ref('')
const page = ref(1)
const size = ref(20)
const total = ref(0)
const rows = ref<UserView[]>([])
const loading = ref(false)
const submitting = ref(false)

const createVisible = ref(false)
const resetVisible = ref(false)
const resetTarget = ref<UserView | null>(null)
const newPassword = ref('')

const createForm = reactive({ username: '', password: '', displayName: '', role: 'user' })

const roleOptions: SelectOption[] = [
  { label: '管理员', value: 'admin' },
  { label: '客服', value: 'agent' },
  { label: '用户', value: 'user' },
]

const pagination = computed<PaginationProps>(() => ({
  page: page.value,
  pageSize: size.value,
  itemCount: total.value,
  showSizePicker: true,
  pageSizes: [20, 50, 100],
  prefix: ({ itemCount }) => `共 ${itemCount} 条`,
  onChange: (p: number) => {
    page.value = p
    void load()
  },
  onUpdatePageSize: (s: number) => {
    size.value = s
    page.value = 1
    void load()
  },
}))

/** 角色 / 状态 → 标签配色。 */
function roleTagType(role: string): 'warning' | 'success' | 'info' {
  if (role === 'admin') return 'warning'
  if (role === 'agent') return 'success'
  return 'info'
}

const columns: DataTableColumns<UserView> = [
  { title: '用户名', key: 'username', width: 140 },
  { title: '展示名', key: 'displayName', width: 140, render: (row) => row.displayName || '—' },
  {
    title: '角色',
    key: 'role',
    width: 110,
    render: (row) =>
      h(
        NTag,
        { size: 'small', bordered: false, type: roleTagType(row.role) },
        { default: () => row.role || '—' },
      ),
  },
  {
    title: '状态',
    key: 'status',
    width: 110,
    render: (row) =>
      h(
        NTag,
        { size: 'small', bordered: false, type: row.status === 'ENABLED' ? 'success' : 'error' },
        { default: () => (row.status === 'ENABLED' ? '已启用' : '已禁用') },
      ),
  },
  { title: '最近登录', key: 'lastLoginAtMs', width: 180, render: (row) => formatTime(row.lastLoginAtMs) },
  { title: '创建时间', key: 'createdAtMs', width: 180, render: (row) => formatTime(row.createdAtMs) },
  {
    title: '操作',
    key: 'actions',
    width: 200,
    fixed: 'right',
    render: (row) =>
      h(NSpace, { size: 6, wrap: false }, {
        default: () => [
          h(
            NButton,
            { size: 'tiny', onClick: () => openReset(row) },
            { default: () => '重置口令' },
          ),
          h(
            NButton,
            {
              size: 'tiny',
              type: row.status === 'ENABLED' ? 'warning' : 'primary',
              // 内置 admin 账号不允许禁用（后端也会拒绝）
              disabled: row.username === 'admin',
              onClick: () => confirmStatus(row),
            },
            { default: () => (row.status === 'ENABLED' ? '禁用' : '启用') },
          ),
        ],
      }),
  },
]

function onSearch(): void {
  page.value = 1
  void load()
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await listUsers({
      keyword: keyword.value.trim() || undefined,
      page: page.value,
      size: size.value,
    })
    rows.value = result.items ?? []
    total.value = result.total ?? 0
  } catch (e) {
    message.error(errMsg(e, '加载用户失败'))
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  createForm.username = ''
  createForm.password = ''
  createForm.displayName = ''
  createForm.role = 'user'
  createVisible.value = true
}

async function submitCreate(): Promise<void> {
  if (!createForm.username.trim() || !createForm.password) {
    message.warning('用户名与口令不能为空')
    return
  }
  submitting.value = true
  try {
    await createUser({
      username: createForm.username.trim(),
      password: createForm.password,
      displayName: createForm.displayName.trim() || createForm.username.trim(),
      role: createForm.role,
    })
    message.success('用户已创建')
    createVisible.value = false
    await load()
  } catch (e) {
    message.error(errMsg(e, '创建用户失败'))
  } finally {
    submitting.value = false
  }
}

function openReset(row: UserView): void {
  resetTarget.value = row
  newPassword.value = ''
  resetVisible.value = true
}

async function submitReset(): Promise<void> {
  const target = resetTarget.value
  if (!target) return
  if (!newPassword.value) {
    message.warning('新口令不能为空')
    return
  }
  submitting.value = true
  try {
    await resetPassword(target.userId, newPassword.value)
    message.success('口令已重置')
    resetVisible.value = false
  } catch (e) {
    message.error(errMsg(e, '重置口令失败'))
  } finally {
    submitting.value = false
  }
}

function confirmStatus(row: UserView): void {
  const next: UserStatus = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  dialog.warning({
    title: next === 'DISABLED' ? '禁用用户' : '启用用户',
    content: `确认${next === 'DISABLED' ? '禁用' : '启用'}用户「${row.displayName || row.username}」？`,
    positiveText: '确认',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await updateUserStatus(row.userId, next)
        message.success(next === 'DISABLED' ? '用户已禁用' : '用户已启用')
        await load()
      } catch (e) {
        message.error(errMsg(e, '操作失败'))
      }
    },
  })
}

/** epoch 毫秒 → 本地时间字符串。 */
function formatTime(epochMs: number): string {
  return epochMs ? new Date(epochMs).toLocaleString() : '—'
}

/** 从后端错误响应提取 Result.message。 */
function errMsg(error: unknown, fallback: string): string {
  const msg = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
  return msg || fallback
}

onMounted(() => void load())
</script>

<style scoped>
/* 外层内边距由 AdminLayout 内容区统一提供 */
.users-page {
  min-width: 0;
}

/* 搜索框宽度自适应，窄屏不撑破 */
.search-input {
  width: clamp(180px, 26vw, 240px);
}

.hint {
  font-size: 13px;
  color: #667085;
}
</style>
