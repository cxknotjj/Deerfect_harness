/** Vite 插件 `vite.docs-meta.ts` 注入的文档修改日期（docs 内相对路径 → YYYY-MM-DD） */
declare module 'virtual:docs-meta' {
  const dates: Record<string, string>
  export default dates
}
