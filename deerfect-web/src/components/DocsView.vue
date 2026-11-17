<script setup lang="ts">
/**
 * 文档页：左侧目录树 + 右侧 Markdown 正文。
 *
 * 数据源是 `docs/registry.ts`——构建时把仓库 docs/ 下的 Markdown 直接打包进前端
 * （Vite `?raw` eager glob），不经过后端；dev 改文档触发 HMR，生产随包发布。
 *
 * 目录、搜索、相对链接跳转都在本地完成；「新建文档」只能生成模板下载
 * （浏览器无写盘权限），页面里已注明落盘位置。
 */
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import {
  DOC_GROUPS,
  DOC_GROUP_DIRS,
  buildDocTemplate,
  docGroupKey,
  docGroupLabel,
  docMarkdown,
  findDoc,
  isValidDocFilename,
  resolveDocLink,
  searchDocs,
  type DocItem,
} from '../docs/registry'
import { fmtBytes } from '../utils/resourceFormat'

const props = defineProps<{ initialPath: string }>()
const emit = defineEmits<{ select: [path: string] }>()

const groups = DOC_GROUPS
const collapsed = ref(new Set<string>(groups.slice(1).map((group) => group.key)))
const selectedPath = ref('')
const selected = ref<(DocItem & { markdown: string }) | null>(null)
const query = ref('')
const note = ref('')
const error = ref('')
const articleEl = ref<HTMLElement | null>(null)

const hits = computed(() => (query.value.trim() ? searchDocs(query.value) : []))

/* ---------- 正文渲染 ---------- */

/** 去掉首个 H1（标题在正文外单独排版，避免与 article 标题重复） */
function stripLeadingH1(markdown: string): string {
  return markdown.replace(/^\uFEFF/, '').replace(/^\s*#\s+[^\r\n]*\r?\n/, '')
}

const bodyHtml = computed(() => {
  if (!selected.value) return ''
  const parsed = marked.parse(stripLeadingH1(selected.value.markdown ?? ''), {
    gfm: true,
    breaks: false,
  })
  return DOMPurify.sanitize(typeof parsed === 'string' ? parsed : '')
})

function docSize(path: string): string {
  const item = findDoc(path)
  return item ? fmtBytes(item.size) : '—'
}

/* ---------- 选择文档 ---------- */

function openDoc(path: string, announce = true): void {
  const markdown = docMarkdown(path)
  const item = findDoc(path)
  if (!markdown || !item) {
    error.value = `文档不存在：${path}`
    return
  }
  error.value = ''
  note.value = ''
  selectedPath.value = path
  selected.value = { ...item, markdown }
  localStorage.setItem('ui-last-doc', path)
  // 选中项所在分组自动展开
  const key = docGroupKey(path)
  if (collapsed.value.has(key)) {
    const next = new Set(collapsed.value)
    next.delete(key)
    collapsed.value = next
  }
  if (announce) emit('select', path)
  void nextTick(() => {
    if (articleEl.value?.scrollTo) articleEl.value.scrollTo({ top: 0 })
  })
}

function toggleGroup(key: string): void {
  const next = new Set(collapsed.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  collapsed.value = next
}

/** 初始文档：地址栏优先，其次上次阅读（本机），最后概览第一篇 */
function initialDocPath(): string {
  const fromUrl = props.initialPath.trim()
  if (fromUrl && findDoc(fromUrl)) return fromUrl
  const last = localStorage.getItem('ui-last-doc') ?? ''
  if (last && findDoc(last)) return last
  return groups[0]?.docs[0]?.path ?? ''
}

onMounted(() => {
  const fromUrl = props.initialPath.trim()
  const path = initialDocPath()
  if (!path) return
  openDoc(path, false)
  // 地址里的文档不在打包范围（如 archive 链接）：就地修正成实际打开的那篇
  if (fromUrl && fromUrl !== path) {
    emit('select', path)
    note.value = `地址中的文档不在目录内：${fromUrl}，已打开「${findDoc(path)?.title ?? path}」`
  }
})

watch(
  () => props.initialPath,
  (path) => {
    if (!path || path === selectedPath.value) return
    if (findDoc(path)) {
      openDoc(path, false)
      return
    }
    // 前进/后退到不存在的文档：退回当前篇并修正地址
    emit('select', selectedPath.value)
    note.value = `地址中的文档不在目录内：${path}`
  },
)

/* ---------- Markdown 内相对链接：在文档页内跳转 ---------- */
function onArticleClick(event: MouseEvent): void {
  const anchor = (event.target as HTMLElement).closest('a')
  if (!anchor) return
  const href = anchor.getAttribute('href') ?? ''
  if (!href) return
  if (/^(?:[a-z][a-z0-9+.-]*:)?\/\//i.test(href) || href.startsWith('mailto:')) {
    anchor.target = '_blank'
    anchor.rel = 'noopener noreferrer'
    return
  }
  if (!href.split(/[?#]/)[0].toLowerCase().endsWith('.md')) return
  event.preventDefault()
  const path = resolveDocLink(selectedPath.value, href)
  if (docMarkdown(path) == null) {
    note.value = `「${path}」是历史存档，未打包进前端（archive/ 不参与目录）`
    return
  }
  openDoc(path)
}

/* ---------- 复制目录 / 新建模板 ---------- */
async function copyRoot(): Promise<void> {
  note.value = ''
  try {
    await navigator.clipboard.writeText('docs/')
    note.value = '已复制 docs/（仓库根下）；浏览器不能直接打开系统目录，粘贴到资源管理器即可'
  } catch {
    note.value = 'docs/ 位于仓库根下；当前浏览器不允许写剪贴板'
  }
}

const creating = ref(false)
const draft = reactive({ group: 'overview', filename: '', title: '' })

const groupOptions = groups.map((group) => ({
  key: group.key,
  label: group.label,
  dir: DOC_GROUP_DIRS[group.key] ?? '',
}))

const templatePath = computed(() => {
  const filename = draft.filename.trim() || 'untitled.md'
  return buildDocTemplate(draft.group, filename, draft.title).path
})

function startCreate(): void {
  creating.value = true
  error.value = ''
  note.value = ''
  draft.group = 'overview'
  draft.filename = ''
  draft.title = ''
}

function downloadMarkdown(filename: string, text: string): void {
  const blob = new Blob([text], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 0)
}

function submitCreate(): void {
  error.value = ''
  const filename = draft.filename.trim()
  if (!isValidDocFilename(filename)) {
    error.value = '文件名需以 .md 结尾，只允许中文、字母、数字、点、下划线或短横线'
    return
  }
  const template = buildDocTemplate(draft.group, filename, draft.title)
  downloadMarkdown(filename, template.markdown)
  creating.value = false
  note.value = `模板已下载：${template.path}。放进仓库 docs/ 后刷新页面即可出现在左侧目录。`
}
</script>

<template>
  <section class="docs-view" aria-label="文档">
    <header class="docs-head">
      <div class="docs-head-text">
        <span class="docs-eyebrow">文档中心</span>
        <h1 class="docs-title">文档</h1>
        <p class="docs-sub">docs/ 目录下的工程文档 · 按分组索引</p>
      </div>
      <div class="docs-tools">
        <label class="docs-search">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <circle cx="11" cy="11" r="7" />
            <path d="m20 20-3.5-3.5" />
          </svg>
          <input
            v-model="query"
            type="search"
            placeholder="搜索文档名或内容…"
            aria-label="搜索文档"
          />
        </label>
        <button class="res-btn" type="button" title="复制 docs/ 目录路径" @click="copyRoot">
          复制目录路径
        </button>
        <button class="res-btn res-btn-primary" type="button" data-testid="docs-create" @click="startCreate">
          + 新建文档
        </button>
      </div>
    </header>

    <p v-if="error" class="res-alert" role="alert">{{ error }}</p>
    <p v-else-if="note" class="res-note" role="status">{{ note }}</p>

    <form v-if="creating" class="docs-create" @submit.prevent="submitCreate">
      <div class="docs-create-head">
        <h3>新建文档</h3>
        <span>浏览器不写 docs/：生成模板后下载，放进对应目录即可出现在左侧</span>
      </div>
      <div class="docs-create-grid">
        <label class="docs-field">
          <span>分组</span>
          <select v-model="draft.group" class="res-input" aria-label="文档分组">
            <option v-for="option in groupOptions" :key="option.key" :value="option.key">
              {{ option.label }}{{ option.dir ? ` · ${option.dir}/` : ' · docs 根层' }}
            </option>
          </select>
        </label>
        <label class="docs-field">
          <span>文件名</span>
          <input v-model="draft.filename" class="res-input" placeholder="my-note.md" required />
        </label>
        <label class="docs-field">
          <span>标题（可选）</span>
          <input v-model="draft.title" class="res-input" placeholder="留空则用文件名" />
        </label>
      </div>
      <div class="docs-create-foot">
        <span class="docs-create-path">落盘位置：docs/{{ templatePath }}</span>
        <button class="res-btn" type="button" @click="creating = false">取消</button>
        <button class="res-btn res-btn-primary" type="submit">下载模板</button>
      </div>
    </form>

    <div class="docs-shell">
      <aside class="docs-tree" aria-label="文档目录">
        <!-- 搜索态：结果列表带片段 -->
        <template v-if="query.trim()">
          <div class="docs-tree-head">
            <span>搜索结果</span>
            <span class="docs-tree-total">{{ hits.length }}</span>
          </div>
          <div class="docs-tree-scroll">
            <p v-if="hits.length === 0" class="docs-tree-empty">没有匹配「{{ query.trim() }}」的文档</p>
            <button
              v-for="hit in hits"
              :key="hit.path"
              class="docs-hit"
              type="button"
              @click="openDoc(hit.path)"
            >
              <span class="docs-hit-title">{{ hit.title }}</span>
              <span class="docs-hit-meta">{{ hit.group }} · {{ hit.path }}</span>
              <span class="docs-hit-snippet">{{ hit.snippet }}</span>
            </button>
          </div>
        </template>

        <!-- 目录态：分组折叠 + 文档条目 -->
        <template v-else>
          <div class="docs-tree-head">
            <span>目录</span>
          </div>
          <div class="docs-tree-scroll">
            <div v-for="group in groups" :key="group.key" class="docs-group">
              <button
                class="docs-group-row"
                type="button"
                :aria-expanded="!collapsed.has(group.key)"
                @click="toggleGroup(group.key)"
              >
                <svg
                  class="docs-chevron"
                  :class="{ 'is-collapsed': collapsed.has(group.key) }"
                  viewBox="0 0 24 24"
                  aria-hidden="true"
                >
                  <path d="m6 9 6 6 6-6" />
                </svg>
                <span>{{ group.label }}</span>
                <span class="docs-group-count">{{ group.count }}</span>
              </button>
              <div v-show="!collapsed.has(group.key)" class="docs-group-items">
                <button
                  v-for="item in group.docs"
                  :key="item.path"
                  class="docs-item"
                  :class="{ 'is-active': item.path === selectedPath }"
                  type="button"
                  :title="item.path"
                  @click="openDoc(item.path)"
                >{{ item.title }}</button>
              </div>
            </div>
          </div>
        </template>
      </aside>

      <article v-if="selected" ref="articleEl" class="docs-article" @click="onArticleClick">
        <div class="docs-crumb">
          <span>{{ docGroupLabel(selected.path) }}</span>
          <span class="docs-crumb-sep">/</span>
          <span class="docs-crumb-title">{{ selected.title }}</span>
          <span class="docs-scope" title="当前文档范围：本仓库 docs/">本项目的</span>
        </div>
        <h1 class="docs-doc-title">{{ selected.title }}</h1>
        <!-- 正文已由 DOMPurify.sanitize 净化（bodyHtml），此处 v-html 安全 -->
        <!-- eslint-disable-next-line vue/no-v-html -->
        <div class="docs-md" v-html="bodyHtml"></div>
        <footer class="docs-foot">
          <span class="res-mono">{{ selected.path }}</span>
          <span>{{ docSize(selected.path) }} · 随前端构建打包，不经过后端</span>
        </footer>
      </article>

      <div v-else class="docs-empty">左侧选择一篇文档</div>
    </div>
  </section>
</template>
