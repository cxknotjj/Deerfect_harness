---
name: "sync-release"
description: "推送当前功能分支到 master 并双远程推送,合并后删除功能分支"
---
# 发布同步:master ← feature/*,双远程推送

> 触发方式:用户说「同步发布」「执行发布」或引用本命令。
> 目标:把当前功能分支合入 master,推送 origin 与 gitee 双远程,结束停回 master。

## 硬性安全规则

1. **任何一步失败立即停止**,原样报告错误输出,等待用户指示;严禁继续执行后续步骤。
2. **严禁** `push --force` / `--force-with-lease` / `reset --hard` / `rebase`;遇到冲突只报告,不擅自解决。
3. **严禁**自动提交或修改任何文件;本命令只做合并与推送,不产生新提交。
4. 每步执行后必须校验结果(当前分支、合并输出、推送输出)再进入下一步。

## 执行步骤

### 0. 前置检查

```bash
git status
git branch --show-current
git remote -v
```

- 若工作树不干净(有未提交/未暂存改动):**停止**,告知用户先处理改动。
- 记录当前分支名(假设为 `feature/xxx`),完成后需切回 master。
- 确认远程存在:必须同时有 `origin`(GitHub)与 `gitee`。
- 确认当前**不在** master 上(若在 master 上,提示用户先切到功能分支)。

### 1. 切到 master,合并功能分支

```bash
git checkout master
git merge <feature-branch-name>
```

- merge 出现冲突:**停止并报告**(说明冲突文件、错误原文),不擅自解决。
- 合并成功后,记录当前 master 的最新 commit hash。

### 2. 推送 master 到双远程

```bash
git push origin master
git push gitee master
```

- 任一 push 被拒:**停止并报告**(说明哪个远程失败、错误原文)。

### 3. 删除已合并的功能分支

```bash
# 删除本地分支
git branch -d <feature-branch-name>

# 删除远程分支(如果之前推过)
git push origin --delete <feature-branch-name>
git push gitee --delete <feature-branch-name>
```

- 若提示「分支未完全合并」,用 `-D` 强制删除前必须再次确认合并已成功。
- 远程分支不存在时忽略错误,继续执行。

### 4. 收尾校验(必须执行)

```bash
git branch --show-current
git log --oneline -n 1 master
git branch -a
```

- 确认当前分支是 **master**;若不是,执行 `git checkout master`。
- 确认功能分支已从本地和远程删除。
- 向用户汇报:master 的最终 commit、双远程同步结果、已删除的功能分支名。
