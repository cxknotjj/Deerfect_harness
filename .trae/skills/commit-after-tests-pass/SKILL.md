---
name: commit-after-tests-pass
description: 修改代码通过测试后按项目 git 规范提交 commit——先跑测试确认全绿、核查改动范围、Conventional Commits 中文描述、git commit -F 防乱码。Use when 用户明确要求「提交 / commit / 提交代码」或要求测试通过后再提交。未经用户明确要求不主动提交，也不推送。
---

# 测试通过后提交 Commit

代码改动完成并**通过测试**后，按本项目 git 规范提交。完整规则见 `.trae/rules/git-workflow.md`（最高优先级：用户未明确说「提交」时禁止任何 git 提交操作）。

## 第一步：跑测试，全绿才准提交

按改动范围选择测试命令，声称提交前必须实际运行并确认输出：

- **Java（server / shared）**：`mvn -pl server -am test`（全量；快速回归可用 `-Dtest='XxxTest'` 针对性跑受影响的测试类）
  - 通过标准：`Tests run` 汇总 0 Failures 0 Errors，BUILD SUCCESS
- **前端（web）**：`cd web && npm run build`（含类型检查与构建）
  - 通过标准：构建成功无报错
- **全栈改动两边都跑**；测试不绿一律先修，修完重跑，绝不带病提交

## 第二步：核查改动范围

1. `git status` + `git diff --stat`——确认暂存内容与本次任务一致
2. 只 add 任务内的文件，**禁止** `git add -A` / `git add .` 盲加
3. 绝不提交：`target/`、`.mvn-repo/`、`web/dist/`、`node_modules/`、IDE 本地配置（`.idea/`、`.vscode/`）、`.env*`、`mcp-config.json`（含 API key，已在 .gitignore）
4. 多个独立改动（如后端修复 + 前端功能）拆成多个 commit，每个只含一个逻辑变更

## 第三步：写 message 并提交

1. 遵循 Conventional Commits：type 前缀英文（`feat:` / `fix:` / `refactor:` / `docs:` / `chore:`），**描述用中文**，正文说明「为什么」而非「做了什么」
2. **防乱码**：message 写入 UTF-8 临时文件后用文件提交，禁止命令行内联中文 `-m "中文"`：

```bash
cat > /tmp/commit-msg.txt <<'EOF'
fix(scope): 中文标题一句话说清做了什么

正文补充动机与关键取舍，一段即可。
EOF
git add <任务内文件...>
git commit -F /tmp/commit-msg.txt
```

3. 提交后 `git status` 确认工作区干净、`git log --oneline -3` 确认提交就位

## 推送（仅当用户明确要求）

- 双远程都要推：`git push gitee master && git push origin master`
- 推送前确认目标分支为 master、无未提交文件
