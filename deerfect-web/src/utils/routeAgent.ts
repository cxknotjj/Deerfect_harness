/**
 * 「路由与 Agent」页的纯展示辅助：卡片摘要、字母块、头像配色、工具列表。
 * 只做字符串整形，不参与接口契约。
 */

/** 路由卡只显示描述里的定位短语（库内描述是「主对话模型：当前绑定…」） */
export function routeSummary(description: string | null): string {
  const trimmed = (description ?? '').trim()
  if (!trimmed) return '未填写描述'
  const head = trimmed.split(/[：:]/)[0]?.trim()
  return head || trimmed
}

/** 路由字母块：短代码取首字母，长代码取前两位（main→M，multi→Mu） */
export function routeMark(code: string): string {
  const normalized = code.trim()
  if (!normalized) return '?'
  // multi 在参考稿里固定显示 Mu；其余代码取首字母，避免 judge/main 被截成两位
  const raw = normalized === 'multi' ? 'Mu' : normalized.slice(0, 1)
  return raw.charAt(0).toUpperCase() + raw.slice(1)
}

const AVATAR_TONES = ['blue', 'purple', 'orange', 'green', 'pink', 'teal'] as const

/** 按 Agent 名稳定取一个头像色调，刷新前后不跳色 */
export function agentTone(name: string): (typeof AVATAR_TONES)[number] {
  let hash = 0
  for (const ch of name) hash = (hash * 31 + ch.charCodeAt(0)) >>> 0
  return AVATAR_TONES[hash % AVATAR_TONES.length]
}

/** 工具字段是逗号分隔（可能带空格）；空值返回空数组 */
export function splitTools(tools: string | null): string[] {
  if (!tools) return []
  return tools
    .split(',')
    .map((tool) => tool.trim())
    .filter((tool) => tool !== '')
}

/** 工具 chips 回写成库内格式；空列表写回 null（后端语义：回退内置分配） */
export function joinTools(tools: string[]): string | null {
  const normalized = tools.map((tool) => tool.trim()).filter((tool) => tool !== '')
  return normalized.length === 0 ? null : normalized.join(',')
}
