#!/usr/bin/env node
/**
 * 从 docs/principal.md（8 条核心约束）+ AGENTS.md（硬规则）生成
 * .specify/memory/constitution.md，供 Spec Kit 的 /plan、/analyze 读取。
 *
 * 用法：
 *   node scripts/sync-constitution.mjs          # 写入
 *   node scripts/sync-constitution.mjs --check  # 只检查，漂移则 exit 1
 *
 * 规则：
 *   - 源文件是唯一事实来源，生成物不得手改；
 *   - 不写时间戳，保证 --check 稳定；
 *   - 相对链接按 .specify/memory/ 的深度重写。
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const PRINCIPAL = join(ROOT, 'docs', 'principal.md')
const AGENTS = join(ROOT, 'AGENTS.md')
const OUTPUT = join(ROOT, '.specify', 'memory', 'constitution.md')

function read(path) {
  return readFileSync(path, 'utf8').replace(/^\uFEFF/, '')
}

/** 去掉 front-matter，返回正文 */
function stripFrontMatter(text) {
  return text.replace(/^---\r?\n[\s\S]*?\r?\n---[ \t]*\r?\n?/, '')
}

/** 从 .specify/memory/ 出发重写仓库内相对链接 */
function rewriteLinks(text) {
  return text
    .replace(/\]\(\.\/([^)]+)\)/g, '](../../docs/$1)')
    .replace(/\]\(\.\.\/([^)]+)\)/g, '](../../$1)')
    .replace(/\]\(docs\//g, '](../../docs/')
    .replace(/\]\(\.trae\//g, '](../../.trae/')
}

function principalSections() {
  const body = stripFrontMatter(read(PRINCIPAL))
  const start = body.search(/^## 1\./m)
  if (start < 0) throw new Error('docs/principal.md 里找不到 “## 1.” 开头的约束段')
  return body
    .slice(start)
    .replace(/^## /gm, '### ')
    .trim()
}

function agentRules() {
  const body = stripFrontMatter(read(AGENTS))
  const heading = body.search(/^## 硬规则\s*$/m)
  if (heading < 0) throw new Error('AGENTS.md 里找不到 “## 硬规则”')
  const rest = body.slice(heading).split(/\r?\n/)
  const lines = []
  for (const line of rest.slice(1)) {
    if (/^## /.test(line)) break
    if (/^细则以/.test(line)) break
    lines.push(line)
  }
  return lines.join('\n').trim()
}

function render() {
  const sections = rewriteLinks(principalSections())
  const rules = rewriteLinks(agentRules())
  return `<!-- GENERATED FILE — DO NOT EDIT -->
<!-- Source: docs/principal.md（1-8）+ AGENTS.md（硬规则） -->
<!-- Regenerate: node scripts/sync-constitution.mjs -->
<!-- Verify:     node scripts/sync-constitution.mjs --check -->

# javaHarness Constitution

> 本文件由脚本生成，**不要手改**。改规则请改 \`docs/principal.md\` 或 \`AGENTS.md\`，然后重跑
> \`node scripts/sync-constitution.mjs\`；CI 用 \`--check\` 阻止漂移。

## Core Principles

${sections}

## Additional Constraints

${rules}

## Governance

- 本文件与 \`docs/principal.md\`、\`AGENTS.md\` 冲突时，以源文件为准，并重跑生成脚本。
- 修订流程：先改源文件 → 重跑脚本 → 提交生成物；三者必须在同一次提交里。
- 不单独维护版本号：规则随源文件变更而更新，历史见 git。
`
}

const next = render()
const check = process.argv.includes('--check')

if (check) {
  if (!existsSync(OUTPUT)) {
    console.error(`缺少 ${OUTPUT}；先跑 node scripts/sync-constitution.mjs`)
    process.exit(1)
  }
  const current = read(OUTPUT)
  if (current !== next) {
    console.error('constitution.md 与源文件不一致：改的是 docs/principal.md 或 AGENTS.md，请重跑生成脚本')
    process.exit(1)
  }
  console.log('constitution.md 与源文件一致')
  process.exit(0)
}

mkdirSync(dirname(OUTPUT), { recursive: true })
writeFileSync(OUTPUT, next, 'utf8')
console.log(`wrote ${OUTPUT} (${next.split('\n').length} lines)`)
