---
name: create-feature-branch
description: 在修改项目代码前创建与任务内容匹配的 feature 分支并切换。Use when the user asks to fix, add, or refactor code and work is still on master —— 建分支后再动手改代码。Do not use for 纯文档、纯查询、或已在目标分支上的场景。
---

# 修改代码前创建任务分支

仓库为单主干工作流:master 是唯一长期分支,功能在短命 feature 分支上进行,完成后经 `/sync-release` 合回并删除。**任何代码修改类任务动手改文件之前**,先确保处于对应分支上。

## 何时跳过

- 当前已在与本次任务匹配的分支上 → 直接干活
- 任务不修改代码(问答、查资料、改纯文档) → 不建分支
- 用户明确说「直接在 master 改」 → 遵用户,但提醒一句后续无法走 /sync-release

## 分支命名

格式 `<type>/<kebab 概述>`,概述取自本次任务内容,2~5 个英文小写词,连字符分隔:

- type 用 Conventional Commits 前缀:`feat` `fix` `refactor` `docs` `test` `build` `chore`
- 示例:任务「修复 CLI 回显污染」 → `fix/cli-echo-pollution`;任务「给 web 端加会话历史」 → `feat/web-chat-history`
- 同名分支已存在且属于旧任务 → 序号递增(`feat/web-chat-history-2`),不确定时问一句

## 创建步骤

1. 前置检查,任一不满足先停下说明:

```bash
git status            # 必须干净,有未提交改动先报告用户
git branch --show-current
```

2. 从最新 master 创建并切换:

```bash
git checkout master && git pull --ff-only origin master
git checkout -b <type>/<kebab-概述>
```

`pull` 失败(本地领先远程属正常)忽略,`ff-only` 保证不会误拉入分叉。

3. 校验 `git branch --show-current` 输出新分支名,再开始改代码。

## 收尾衔接

代码提交后走 `/sync-release` 命令:合入 master、双远程推送、删除本分支。不要在 feature 分支上直接 push 长期留存。
