/**
 * public 资源路径：必须拼上 Vite 的 BASE_URL。
 * GitHub Pages 部署在 /Deerfect_harness/ 下，根路径 /deerfect-mark.svg 会 404，
 * 所以组件里不要再写死以 / 开头的 public 路径。
 */

export function withBase(base: string, file: string): string {
  const root = base && base !== '/' ? base.replace(/\/+$/, '') : ''
  return `${root}/${file.replace(/^\/+/, '')}`
}

export const BRAND_MARK = withBase(import.meta.env.BASE_URL, 'deerfect-mark.svg')
export const BRAND_MARK_WHITE = withBase(import.meta.env.BASE_URL, 'deerfect-mark-white.svg')
