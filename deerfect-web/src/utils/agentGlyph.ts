/**
 * 内置 Agent 的专属图标：统一 24×24 线性图形，按 agent name 一对一映射。
 *
 * 图标只在前端展示，不落库；新增/自定义 Agent 未命中映射时由组件回退到首字母，
 * 所以这里不需要覆盖用户后续注册的 Agent。
 */

export type AgentGlyphKey =
  | 'sparkles'
  | 'atom'
  | 'network'
  | 'compass'
  | 'merge'
  | 'search'
  | 'code'
  | 'chart'
  | 'pen'
  | 'dragon'
  | 'coffee'
  | 'gavel'
  | 'box'
  | 'profile'
  | 'document'
  | 'bolt'

/** 实心小圆点（如原子核、网络中心） */
export interface AgentGlyphDot {
  cx: number
  cy: number
  r: number
  filled?: boolean
}

/** 旋转椭圆（原子轨道） */
export interface AgentGlyphEllipse {
  cx: number
  cy: number
  rx: number
  ry: number
  rotate?: number
}

export interface AgentGlyphSpec {
  key: AgentGlyphKey
  label: string
  paths: string[]
  dots?: AgentGlyphDot[]
  ellipses?: AgentGlyphEllipse[]
}

/** 16 个内置 Agent 的图形；全部唯一，避免两个 Agent 撞图标 */
const GLYPHS: Record<AgentGlyphKey, AgentGlyphSpec> = {
  sparkles: {
    key: 'sparkles',
    label: '通用助手',
    paths: [
      'M10 3.6l1.6 4.8 4.8 1.6-4.8 1.6L10 16.4l-1.6-4.8L3.6 10l4.8-1.6z',
      'M18 15.6l.7 1.7 1.7.7-1.7.7-.7 1.7-.7-1.7-1.7-.7 1.7-.7z',
    ],
  },
  atom: {
    key: 'atom',
    label: '深度推理',
    paths: [],
    dots: [{ cx: 12, cy: 12, r: 1.5, filled: true }],
    ellipses: [
      { cx: 12, cy: 12, rx: 8.2, ry: 3.4, rotate: 0 },
      { cx: 12, cy: 12, rx: 8.2, ry: 3.4, rotate: 60 },
      { cx: 12, cy: 12, rx: 8.2, ry: 3.4, rotate: 120 },
    ],
  },
  network: {
    key: 'network',
    label: '多 Agent 编排',
    paths: ['M12 12L5.4 6.2', 'M12 12l6.6-5.8', 'M12 12v6.6'],
    dots: [
      { cx: 12, cy: 12, r: 2.1, filled: true },
      { cx: 5.2, cy: 5.2, r: 2 },
      { cx: 18.8, cy: 5.2, r: 2 },
      { cx: 12, cy: 20, r: 2 },
    ],
  },
  compass: {
    key: 'compass',
    label: '任务拆解',
    paths: [
      'M12 6.4l2.7 7.8-2.7-1.7-2.7 1.7z',
      'M12 2.8v1.4',
      'M12 19.8v1.4',
      'M2.8 12h1.4',
      'M19.8 12h1.4',
    ],
    dots: [{ cx: 12, cy: 12, r: 8.2 }],
  },
  merge: {
    key: 'merge',
    label: '结果聚合',
    paths: [
      'M5 5l7 7',
      'M12 4v8',
      'M19 5l-7 7',
      'M12 12v5.6',
      'M9.4 15.4L12 18l2.6-2.6',
    ],
  },
  search: {
    key: 'search',
    label: '资料调研',
    paths: ['M14.6 14.6L20.5 20.5'],
    dots: [{ cx: 10.2, cy: 10.2, r: 5.8 }],
  },
  code: {
    key: 'code',
    label: '代码开发',
    paths: ['M8.4 7.2L3.8 12l4.6 4.8', 'M15.6 7.2L20.2 12l-4.6 4.8', 'M13.6 4.4l-3.2 15.2'],
  },
  chart: {
    key: 'chart',
    label: '数据分析',
    paths: ['M4 4v16h16', 'M8 20v-4.6', 'M12 20v-8.6', 'M16.4 20v-12.4'],
  },
  pen: {
    key: 'pen',
    label: '汇总撰写',
    paths: [
      'M20 4c-5.6.2-9.8 4.3-11.4 10.2L6.4 20l5.7-2.2C17.9 16.2 21 11.8 20 4z',
      'M6.4 20l6.8-6.8',
    ],
  },
  dragon: {
    key: 'dragon',
    label: '奶龙',
    paths: [
      'M8.8 9.2l-.9-3.4 3.3 1.2',
      'M15.2 9.2l.9-3.4-3.3 1.2',
      'M9.2 15.6c1.8 1.5 3.8 1.5 5.6 0',
      'M6.2 13.6l-2.2-.7',
      'M17.8 13.6l2.2-.7',
    ],
    dots: [
      { cx: 12, cy: 13, r: 5.4 },
      { cx: 9.8, cy: 12.2, r: 0.7, filled: true },
      { cx: 14.2, cy: 12.2, r: 0.7, filled: true },
    ],
  },
  coffee: {
    key: 'coffee',
    label: 'Java 导师',
    paths: [
      'M4.4 9h11v5.6a4 4 0 0 1-4 4h-3a4 4 0 0 1-4-4z',
      'M15.4 10.2h2a2.7 2.7 0 0 1 0 5.4h-2',
      'M3.4 20.6h13',
      'M7.4 6.2c-1-1.1 1-2.1 0-3.2',
      'M11 6.2c-1-1.1 1-2.1 0-3.2',
    ],
  },
  gavel: {
    key: 'gavel',
    label: '路由判断',
    paths: [
      'M12.4 3.6l8 8-3.2 3.2-8-8z',
      'M10.4 9.2L4 15.6',
      'M12.8 20h7.2',
      'M5.4 19.4l2.6 1.2',
    ],
  },
  box: {
    key: 'box',
    label: '仓储专员',
    paths: ['M12 3.2l8 4.4v8.8L12 20.8l-8-4.4V7.6z', 'M4 7.6l8 4.4 8-4.4', 'M12 12v8.8'],
  },
  profile: {
    key: 'profile',
    label: '用户画像',
    paths: ['M5 20c.3-3.8 3.2-5.8 7-5.8s6.7 2 7 5.8'],
    dots: [{ cx: 12, cy: 8.2, r: 3.4 }],
  },
  document: {
    key: 'document',
    label: '会话摘要',
    paths: [
      'M6 3h7.5L18 7.5V21H6z',
      'M13.5 3v4.5H18',
      'M9 11h6',
      'M9 14.5h6',
      'M9 18h3.5',
    ],
  },
  bolt: {
    key: 'bolt',
    label: '触发判断',
    paths: ['M13.2 3L5 13.5h5.7L9.8 21 18 10.5h-5.7z'],
  },
}

/** 内置 Agent 注册标识 → 图形；仅覆盖当前内置的 16 个 */
export const BUILTIN_AGENT_GLYPHS: Readonly<Record<string, AgentGlyphKey>> = {
  general: 'sparkles',
  deepseek: 'atom',
  'multi-agent': 'network',
  lead: 'compass',
  aggregator: 'merge',
  researcher: 'search',
  coder: 'code',
  analyst: 'chart',
  writer: 'pen',
  nailong: 'dragon',
  'java-tutor': 'coffee',
  'route-judge': 'gavel',
  wms: 'box',
  profile: 'profile',
  summary: 'document',
  'trigger-judge': 'bolt',
}

/** 取内置 Agent 的图标键；大小写/空白不敏感，未命中返回 null */
export function agentGlyphKey(name: string): AgentGlyphKey | null {
  const normalized = name.trim().toLowerCase()
  if (!normalized) return null
  return BUILTIN_AGENT_GLYPHS[normalized] ?? null
}

/** 取图形定义；未命中返回 null，调用方回退首字母 */
export function agentGlyphFor(name: string): AgentGlyphSpec | null {
  const key = agentGlyphKey(name)
  return key ? GLYPHS[key] : null
}
