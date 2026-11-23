#!/usr/bin/env node
/**
 * 把 deerfect-web/dist 发布到 gh-pages 分支根目录（GitHub Pages 项目页）。
 *
 * 用临时 git worktree 检出 origin/gh-pages，覆盖构建产物后提交并推送，
 * 不切换当前分支、不把产物写进主工作区。docs/README 等非构建文件原样保留。
 *
 * 用法：
 *   npm run deploy:pages                      # 构建 + 发布
 *   node scripts/deploy-pages.mjs             # 只发布现有 dist
 *   node scripts/deploy-pages.mjs --no-push   # 只提交不推送，保留临时 worktree 供检查
 */
import { execFileSync } from 'node:child_process'
import { cpSync, existsSync, mkdtempSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const webDir = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const repoDir = resolve(webDir, '..')
const distDir = join(webDir, 'dist')
const remoteBranch = 'gh-pages'
const noPush = process.argv.includes('--no-push')

/** 仓库根目录下由构建产出的文件：发布前先删掉，避免上一版哈希产物残留 */
const BUILD_FILES =
  /^(index\.html|404\.html|sw\.js|registerSW\.js|manifest\.webmanifest|workbox-[^/]+\.js|deerfect-(mark|lockup)[^/]*)$/

function git(args, cwd = repoDir) {
  execFileSync('git', args, { cwd, stdio: 'inherit' })
}

function output(args, cwd = repoDir) {
  return execFileSync('git', args, { cwd, encoding: 'utf8' }).trim()
}

if (!existsSync(join(distDir, 'index.html'))) {
  console.error('[pages] 找不到 deerfect-web/dist/index.html，请先执行 npm run build:pages')
  process.exit(1)
}

console.log(`[pages] 拉取 origin/${remoteBranch} ...`)
git(['fetch', 'origin', remoteBranch])

const tempRoot = mkdtempSync(join(tmpdir(), 'deerfect-pages-'))
const worktree = join(tempRoot, 'wt')
let worktreeAdded = false

try {
  git(['worktree', 'add', '--detach', worktree, `origin/${remoteBranch}`])
  worktreeAdded = true

  // 清掉上一版构建产物；docs/README/diagrams 等非构建内容不动
  for (const name of ['assets', 'icons']) {
    rmSync(join(worktree, name), { recursive: true, force: true })
  }
  for (const name of readdirSync(worktree)) {
    if (BUILD_FILES.test(name)) {
      rmSync(join(worktree, name), { recursive: true, force: true })
    }
  }

  cpSync(distDir, worktree, { recursive: true })
  writeFileSync(join(worktree, '.nojekyll'), '')

  git(['add', '-A'], worktree)
  if (output(['status', '--porcelain'], worktree) === '') {
    console.log('[pages] 构建产物无变化，跳过发布')
  } else {
    const sha = output(['rev-parse', '--short', 'HEAD'])
    const msgFile = join(tempRoot, 'commit-msg.txt')
    writeFileSync(msgFile, `chore(pages): 发布 ${sha} 构建\n`, 'utf8')
    git(['commit', '-F', msgFile], worktree)

    if (noPush) {
      console.log(`[pages] 已提交未推送，临时 worktree 保留在：${worktree}`)
      worktreeAdded = false
    } else {
      // 先用本地分支兜住提交：推送失败（断网/连接重置）时 commit 不会随临时 worktree 一起丢掉
      git(['branch', '-f', 'pages-deploy', 'HEAD'], worktree)
      console.log(`[pages] 推送 origin ${remoteBranch} ...`)
      let pushed = true
      try {
        git(['push', 'origin', `pages-deploy:${remoteBranch}`])
      } catch {
        pushed = false
        process.exitCode = 1
        console.error('[pages] 推送失败，提交已保存在本地分支 pages-deploy')
        console.error('[pages] 网络恢复后重试：git push origin pages-deploy:gh-pages')
      }
      if (pushed) {
        try {
          git(['branch', '-D', 'pages-deploy'])
        } catch {
          // 本地兜底分支清理失败不影响发布结果
        }
        console.log('[pages] 发布完成：https://deerfect.me/Deerfect_harness/')
      }
    }
  }
} finally {
  if (worktreeAdded) {
    try {
      git(['worktree', 'remove', '--force', worktree])
      rmSync(tempRoot, { recursive: true, force: true })
    } catch {
      console.warn(`[pages] 临时 worktree 清理失败，可手动删除：${worktree}`)
    }
  }
}
