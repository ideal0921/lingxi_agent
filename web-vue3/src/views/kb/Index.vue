<template>
  <!-- 知识库运营页（M5-5.6 子任务 6.5）：知识库选择/管理 + 分类 + 文档运营，浅色卡片风格与「运营报表」一致 -->
  <div class="kb-page">
    <!-- ① 知识库：选择 + 新建 + 编辑 + 启用/停用 -->
    <n-card size="small" title="知识库">
      <template #header-extra>
        <n-button size="small" type="primary" @click="openCreateKb">新建知识库</n-button>
      </template>
      <n-space align="center" :size="12" :wrap="true">
        <n-select
          :value="currentKbId"
          :options="kbOptions"
          :loading="loadingBases"
          placeholder="选择知识库"
          style="width: 240px"
          @update:value="onKbSelect"
        />
        <n-button size="small" :disabled="!currentKb" @click="openEditKb">编辑名称/描述</n-button>
        <n-button
          size="small"
          :disabled="!currentKb"
          :type="currentKb?.status === 'ENABLED' ? 'warning' : 'primary'"
          @click="confirmToggleStatus"
        >
          {{ currentKb?.status === 'ENABLED' ? '停用' : '启用' }}
        </n-button>
        <!-- 当前知识库状态一目了然；未选时给出引导 -->
        <n-tag v-if="currentKb" size="small" :bordered="false" :type="currentKb.status === 'ENABLED' ? 'success' : 'error'">
          {{ currentKb.status === 'ENABLED' ? '已启用' : '已停用' }}
        </n-tag>
        <n-tag v-else size="small" :bordered="false">未选择知识库</n-tag>
      </n-space>
      <n-alert v-if="!loadingBases && bases.length === 0" type="info" :show-icon="false" size="small" style="margin-top: 12px">
        暂无知识库，点击右上角「新建知识库」开始。
      </n-alert>
    </n-card>

    <!-- ② 分类管理：标签式列表 + 新建 + 删除（需确认） -->
    <n-card size="small" title="分类管理">
      <template #header-extra>
        <n-button size="small" :disabled="!currentKb" @click="openCreateCategory">新建分类</n-button>
      </template>
      <n-empty v-if="!currentKb" description="请先选择或新建知识库" size="small" />
      <n-empty v-else-if="loadingCategories" description="加载中…" size="small" />
      <n-empty v-else-if="categories.length === 0" description="该知识库暂无分类" size="small" />
      <n-space v-else :size="8">
        <n-tag
          v-for="category in categories"
          :key="category.categoryId"
          closable
          size="small"
          @close="confirmDeleteCategory(category)"
        >
          {{ category.name }}
        </n-tag>
      </n-space>
    </n-card>

    <!-- ③ 文档运营：上传 + 状态筛选 + 表格 + 下线 -->
    <n-card size="small" title="文档">
      <template #header-extra>
        <n-space align="center" :size="8">
          <n-select
            v-model:value="statusFilter"
            :options="statusOptions"
            size="small"
            clearable
            placeholder="全部状态"
            style="width: 150px"
            @update:value="() => void loadDocuments()"
          />
          <n-button size="small" :disabled="!currentKb" :loading="loadingDocuments" @click="loadDocuments">刷新</n-button>
        </n-space>
      </template>
      <n-empty v-if="!currentKb" description="请先选择或新建知识库" size="small" />
      <template v-else>
        <n-space align="center" :size="12" :wrap="true" style="margin-bottom: 12px">
          <!-- 上传期间通过 disabled 禁用入口，天然防止重复提交 -->
          <n-upload :show-file-list="false" :custom-request="onUploadRequest" :disabled="uploading">
            <n-button size="small" type="primary" :disabled="uploading">选择文件上传</n-button>
          </n-upload>
          <n-progress v-if="uploading" type="line" :percentage="uploadPercent" :height="14" style="max-width: 260px" />
          <span class="hint">{{ uploading ? uploadStage : '≤4MB 单请求上传；>4MB 自动分片（每片 4MB）' }}</span>
        </n-space>
        <n-data-table
          :columns="columns"
          :data="documents"
          :loading="loadingDocuments"
          :bordered="false"
          :single-line="false"
          :scroll-x="1120"
          :row-key="(row: DocumentView) => row.docId"
          size="small"
        >
          <template #empty>
            <n-empty description="该知识库暂无文档" size="small" />
          </template>
        </n-data-table>
      </template>
    </n-card>
  </div>

  <!-- 新建 / 编辑知识库：共用同一表单，按 mode 切换标题与提交分支 -->
  <n-modal v-model:show="kbVisible" preset="card" :title="kbMode === 'create' ? '新建知识库' : '编辑知识库'" style="width: min(460px, 92vw)">
    <n-space vertical :size="12">
      <n-input v-model:value="kbForm.name" placeholder="知识库名称" />
      <n-input v-model:value="kbForm.description" type="textarea" placeholder="描述（可选）" :rows="3" />
      <n-button type="primary" block :loading="submitting" @click="submitKb">保存</n-button>
    </n-space>
  </n-modal>

  <!-- 新建分类 -->
  <n-modal v-model:show="categoryVisible" preset="card" title="新建分类" style="width: min(420px, 92vw)">
    <n-space vertical :size="12">
      <n-input v-model:value="categoryName" placeholder="分类名称" @keyup.enter="submitCategory" />
      <n-button type="primary" block :loading="submitting" @click="submitCategory">创建</n-button>
    </n-space>
  </n-modal>
</template>

<script setup lang="ts">
import { computed, h, onMounted, reactive, ref, type VNode } from 'vue'
import {
  NAlert,
  NButton,
  NCard,
  NDataTable,
  NEllipsis,
  NEmpty,
  NInput,
  NModal,
  NProgress,
  NSelect,
  NSpace,
  NTag,
  NUpload,
  useDialog,
  useMessage,
  type DataTableColumns,
  type SelectOption,
  type UploadCustomRequestOptions,
} from 'naive-ui'
import {
  SINGLE_UPLOAD_LIMIT_BYTES,
  createBase,
  createCategory,
  deleteCategory,
  deleteDocument,
  listBases,
  listCategories,
  listDocuments,
  updateBase,
  updateBaseStatus,
  uploadDocumentAuto,
  type CategoryView,
  type DocStatus,
  type DocumentView,
  type KbStatus,
  type KbView,
} from '../../api/kb'

const message = useMessage()
const dialog = useDialog()

const bases = ref<KbView[]>([])
const currentKbId = ref<string | null>(null)
const categories = ref<CategoryView[]>([])
const documents = ref<DocumentView[]>([])
const statusFilter = ref<DocStatus | null>(null)

const loadingBases = ref(false)
const loadingCategories = ref(false)
const loadingDocuments = ref(false)
const submitting = ref(false)
const uploading = ref(false)
const uploadPercent = ref(0)
const uploadStage = ref('')

const kbVisible = ref(false)
const kbMode = ref<'create' | 'edit'>('create')
const kbForm = reactive({ name: '', description: '' })

const categoryVisible = ref(false)
const categoryName = ref('')

/** 当前选中的知识库（未选或无匹配时为 null）。 */
const currentKb = computed<KbView | null>(
  () => bases.value.find((item) => item.kbId === currentKbId.value) ?? null,
)

const kbOptions = computed<SelectOption[]>(() =>
  bases.value.map((item) => ({ label: item.name, value: item.kbId })),
)

/** 切片状态筛选项，取值与后端 DocStatus 一一对应。 */
const statusOptions: SelectOption[] = [
  { label: '处理中', value: 'PENDING' },
  { label: '已入库', value: 'INDEXED' },
  { label: '失败', value: 'FAILED' },
  { label: '已下线', value: 'DELETED' },
]

const columns: DataTableColumns<DocumentView> = [
  { title: '标题', key: 'title', width: 180, ellipsis: { tooltip: true } },
  { title: '文件名', key: 'fileName', width: 180, ellipsis: { tooltip: true } },
  { title: '类型', key: 'docType', width: 90, render: (row) => row.docType || '—' },
  { title: '版本', key: 'version', width: 70, render: (row) => `v${row.version}` },
  { title: '大小', key: 'sizeBytes', width: 100, render: (row) => formatSize(row.sizeBytes) },
  { title: '切片状态', key: 'status', width: 110, render: (row) => statusTag(row.status) },
  {
    title: '失败原因',
    key: 'statusMessage',
    width: 200,
    // 仅 FAILED 展示原因；超长时以省略号收敛并保留悬浮完整内容
    render: (row) =>
      row.status === 'FAILED'
        ? h(NEllipsis, { style: 'max-width: 180px' }, { default: () => row.statusMessage || '未知原因' })
        : '—',
  },
  { title: '子块数', key: 'childCount', width: 80 },
  { title: '父块数', key: 'parentCount', width: 80 },
  { title: '更新时间', key: 'updatedAt', width: 170, render: (row) => formatTime(row.updatedAt) },
  {
    title: '操作',
    key: 'actions',
    width: 90,
    fixed: 'right',
    render: (row) =>
      h(
        NButton,
        {
          size: 'tiny',
          type: 'error',
          // 已下线文档无需重复下线
          disabled: row.status === 'DELETED',
          onClick: () => confirmOffline(row),
        },
        { default: () => '下线' },
      ),
  },
]

onMounted(() => void loadBases())

/**
 * 加载知识库列表并按需选中。
 * selectKbId 用于「新建后希望直接选中新库」的场景，避免再让用户手动选择一次。
 */
async function loadBases(selectKbId?: string): Promise<void> {
  loadingBases.value = true
  try {
    bases.value = (await listBases()) ?? []
    if (selectKbId && bases.value.some((item) => item.kbId === selectKbId)) {
      currentKbId.value = selectKbId
    } else if (!bases.value.some((item) => item.kbId === currentKbId.value)) {
      // 当前选中项已不存在（如被删除）：回落到第一项或清空
      currentKbId.value = bases.value[0]?.kbId ?? null
    }
    await loadKnowledgeContent()
  } catch (error) {
    message.error(errMsg(error, '加载知识库失败'))
  } finally {
    loadingBases.value = false
  }
}

/** 切换知识库后刷新分类与文档（两请求互不依赖，故并发发起）。 */
async function loadKnowledgeContent(): Promise<void> {
  if (!currentKbId.value) {
    categories.value = []
    documents.value = []
    return
  }
  await Promise.all([loadCategories(), loadDocuments()])
}

async function loadCategories(): Promise<void> {
  const kbId = currentKbId.value
  if (!kbId) return
  loadingCategories.value = true
  try {
    categories.value = (await listCategories(kbId)) ?? []
  } catch (error) {
    message.error(errMsg(error, '加载分类失败'))
  } finally {
    loadingCategories.value = false
  }
}

async function loadDocuments(): Promise<void> {
  const kbId = currentKbId.value
  if (!kbId) return
  loadingDocuments.value = true
  try {
    documents.value = (await listDocuments({ kbId, status: statusFilter.value ?? undefined })) ?? []
  } catch (error) {
    message.error(errMsg(error, '加载文档失败'))
  } finally {
    loadingDocuments.value = false
  }
}

function onKbSelect(kbId: string): void {
  currentKbId.value = kbId
  void loadKnowledgeContent()
}

function openCreateKb(): void {
  kbMode.value = 'create'
  kbForm.name = ''
  kbForm.description = ''
  kbVisible.value = true
}

function openEditKb(): void {
  const kb = currentKb.value
  if (!kb) return
  kbMode.value = 'edit'
  kbForm.name = kb.name
  kbForm.description = kb.description ?? ''
  kbVisible.value = true
}

async function submitKb(): Promise<void> {
  const name = kbForm.name.trim()
  if (!name) {
    message.warning('知识库名称不能为空')
    return
  }
  const description = kbForm.description.trim()
  submitting.value = true
  try {
    if (kbMode.value === 'create') {
      const created = await createBase({ name, description })
      message.success('知识库已创建')
      kbVisible.value = false
      await loadBases(created.kbId)
    } else {
      const kb = currentKb.value
      if (!kb) return
      await updateBase(kb.kbId, { name, description })
      message.success('知识库已更新')
      kbVisible.value = false
      await loadBases()
    }
  } catch (error) {
    message.error(errMsg(error, '保存知识库失败'))
  } finally {
    submitting.value = false
  }
}

function confirmToggleStatus(): void {
  const kb = currentKb.value
  if (!kb) return
  const next: KbStatus = kb.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  dialog.warning({
    title: next === 'DISABLED' ? '停用知识库' : '启用知识库',
    content: `确认${next === 'DISABLED' ? '停用' : '启用'}知识库「${kb.name}」？`,
    positiveText: '确认',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await updateBaseStatus(kb.kbId, next)
        message.success(next === 'DISABLED' ? '知识库已停用' : '知识库已启用')
        await loadBases()
      } catch (error) {
        message.error(errMsg(error, '操作失败'))
      }
    },
  })
}

function openCreateCategory(): void {
  categoryName.value = ''
  categoryVisible.value = true
}

async function submitCategory(): Promise<void> {
  const kb = currentKb.value
  const name = categoryName.value.trim()
  if (!kb) {
    message.warning('请先选择知识库')
    return
  }
  if (!name) {
    message.warning('分类名称不能为空')
    return
  }
  submitting.value = true
  try {
    await createCategory(kb.kbId, name)
    message.success('分类已创建')
    categoryVisible.value = false
    await loadCategories()
  } catch (error) {
    message.error(errMsg(error, '创建分类失败'))
  } finally {
    submitting.value = false
  }
}

function confirmDeleteCategory(category: CategoryView): void {
  dialog.warning({
    title: '删除分类',
    content: `确认删除分类「${category.name}」？`,
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deleteCategory(category.categoryId)
        message.success('分类已删除')
        await loadCategories()
      } catch (error) {
        message.error(errMsg(error, '删除分类失败'))
      }
    },
  })
}

/**
 * 上传文件：≤4MB 单请求直传，>4MB 由 uploadDocumentAuto 自动分片并回报进度。
 * 组件已在上传期间禁用入口，这里再守一道，避免极端情况下并发提交。
 */
async function onUploadRequest(options: UploadCustomRequestOptions): Promise<void> {
  const kb = currentKb.value
  const file = options.file.file
  if (!kb || !file || uploading.value) {
    options.onError()
    return
  }
  uploading.value = true
  uploadPercent.value = 0
  uploadStage.value = file.size > SINGLE_UPLOAD_LIMIT_BYTES ? '分片上传中' : '上传中'
  try {
    await uploadDocumentAuto({ kbId: kb.kbId, file, title: file.name }, (percent) => {
      uploadPercent.value = percent
    })
    options.onFinish()
    message.success('文档已上传，正在后台入库')
    await loadDocuments()
  } catch (error) {
    options.onError()
    message.error(errMsg(error, '上传失败'))
  } finally {
    uploading.value = false
    uploadStage.value = ''
  }
}

function confirmOffline(row: DocumentView): void {
  dialog.warning({
    title: '下线文档',
    content: `确认下线文档「${row.title || row.fileName}」？下线后不再参与检索。`,
    positiveText: '确认下线',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deleteDocument(row.docId)
        message.success('文档已下线')
        await loadDocuments()
      } catch (error) {
        message.error(errMsg(error, '下线失败'))
      }
    },
  })
}

/** 切片状态 → 彩色标签（颜色语义与后端状态一一对应）。 */
function statusTag(status: DocStatus): VNode {
  const meta: Record<DocStatus, { label: string; type: 'info' | 'success' | 'error' | 'default' }> = {
    PENDING: { label: '处理中', type: 'info' },
    INDEXED: { label: '已入库', type: 'success' },
    FAILED: { label: '失败', type: 'error' },
    DELETED: { label: '已下线', type: 'default' },
  }
  const item = meta[status] ?? { label: status, type: 'default' as const }
  return h(NTag, { size: 'small', bordered: false, type: item.type }, { default: () => item.label })
}

/** 字节 → 人类可读大小（KB/MB），保留一位小数。 */
function formatSize(bytes: number): string {
  if (!bytes) return '0 KB'
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

/** 后端时间串（ISO）→ 本地时间文案；不可解析时原样返回。 */
function formatTime(value: string): string {
  if (!value) return '—'
  const time = new Date(value)
  return Number.isNaN(time.getTime()) ? value : time.toLocaleString()
}

/** 从后端错误提取 Result.message（http 拦截器保留原始 error.response）。 */
function errMsg(error: unknown, fallback: string): string {
  const data = (error as { response?: { data?: { message?: string } } })?.response?.data
  const own = (error as { message?: string })?.message
  return data?.message || own || fallback
}
</script>

<style scoped>
/*
 * 页面铺满内容区剩余宽高：剩余高度由 AdminLayout 的 .content-inner.is-fill-height 提供，
 * 此处纵向 flex 列承载卡片并吃掉剩余高度；去掉原 max-width 居中限宽（宽屏不再两侧留白），
 * 内容超出时页面内滚动。
 */
.kb-page {
  flex: 1 1 auto;
  min-height: 0;
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 14px;
  overflow-y: auto;
}

.hint {
  font-size: 12px;
  color: #98a2b3;
}
</style>
