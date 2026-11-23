---
title: "本地开发环境"
order: 8
---

# 本地开发环境

> 只记录三类知识：**环境事实**（这台机器与这个项目是什么）、**本机操作**（在本机怎么做）、
> **FAQ**（实测踩过的坑）。全文按这三类分节，不设第四类标题。
>
> 不重复 [README](../README.md) 的快速开始；首次部署见 [guides/setup.md](./guides/setup.md)，
> 运维口径见 [ops.md](./ops.md)，容器与镜像见 [guides/docker-deploy.md](./guides/docker-deploy.md)，
> 后端测试细节见 [guides/functional-testing.md](./guides/functional-testing.md)；
> 渠道怎么配见 [channel-email/README.md](../channel-email/README.md)、
> [channel-feishu/README.md](../channel-feishu/README.md)、
> [channel-qq/README.md](../channel-qq/README.md)。
>
> **命令以本文件为单源**：其它文档只写「该验证什么」，需要执行时回到本文的
> 「Maven 常用命令」「前端开发与验证」，不复制命令行。
>
> 改端口、改启动方式、换依赖服务时，同一次提交内更新本文件；版本号与路径每季度核对一次。
>
> 最后按代码核对：2026-11-23

## 〇、速查

```bash
set -a; source .env.local; set +a                               # 手工启动/mvn 前；run-wsl.sh 会自己加载
mvn -s .mvn/settings.xml -pl deerfect-server spring-boot:run     # 起服务（8080）
mvn -s .mvn/settings.xml verify                                  # 全量测试 + 覆盖率门禁（提交前）
cd deerfect-web && npm run dev                                   # 前端（80，proxy 到 8080）
```

## 一、环境事实

### 工具链（Windows 侧实测）

| 工具         | 版本                                   | 说明                             |
| ---------- | ------------------------------------ | ------------------------------ |
| JDK        | 25（Microsoft build，`D:\env\jdk-25`）  | `JAVA_HOME` 必须指向它              |
| Maven      | 3.9.4（`D:\apache-maven-3.9.4`）       | 仓库没有 `mvnw`，直接用 `mvn`          |
| Node / npm | v22.20.0 / 10.9.3                    | 只给 `deerfect-web/` 用；经 nvm 安装，见「前端运行要求」 |
| MySQL      | 8.4.5（`D:\mysql\mysql-8.4.5-winx64`） | 见「端口与依赖服务」                     |

`.mvn/settings.xml` 是本机文件，**不在仓库里**（`.gitignore` 排除了 `.mvn/`）：它把本地仓库
指向 `.mvn-repo/`（仓库内）并配置阿里云镜像。干净 clone、CI 与镜像构建都没有它——直接 `mvn`
会走默认 `~/.m2` 和公网 central，能构建但慢，不是错误。下文命令按本机有该文件来写。

### 端口与依赖服务

| 组件            | 地址                            | 是否必需   | 说明                                                                                                  |
| ------------- | ----------------------------- | ------ | --------------------------------------------------------------------------------------------------- |
| server        | `8080`                        | 必需     | REST + SSE；MCP 端点在同端口 `/mcp`                                                                        |
| Web 开发服务器     | `80`                          | 只在调前端时 | vite，proxy 到 8080；Windows 监听 80 需管理员权限（或改 `vite.config.ts` 的 `server.port`）                     |
| MySQL         | `127.0.0.1:3306`，库 `harness`  | 必需     | 配置见 `application-datasource.yaml`                                                                   |
| PgVector      | `127.0.0.1:5432`，库 `postgres` | 可选     | 仅知识库（RAG）；未启用时 `/api/knowledge*` 返回 503                                                             |
| Docker daemon | `127.0.0.1:2375`              | 可选     | 沙箱工具；**必须写 `127.0.0.1`，不要写 `localhost`**（IPv6 解析会超时）                                                |
| NapCat        | `127.0.0.1:3000`              | 可选     | QQ 渠道；`napcat.enabled=false` 时整链不装配；远程联调见 [channel-qq/README.md](../channel-qq/README.md)              |
| Edge TTS      | Python 解释器（`TTS_PYTHON`）      | 可选     | 语音合成（QQ / 飞书）；需 `pip install edge-tts`。飞书还要 ffmpeg 转 opus（`CHANNEL_FEISHU_WS_VOICE_FFMPEG`），缺了只退回文字 |

### 数据库迁移（Flyway）

Flyway 随 deerfect-server 启动自动执行 `deerfect-server/src/main/resources/db/migration/` 下的脚本；
新增迁移取**目录里当前最大版本号 +1**，已应用的脚本不可再改。

这里不记录「当前版本是多少」——数字每次加迁移都会变，权威来源就是那个目录。
各版本做了什么见 [project-structure.md](./project-structure.md) 的「数据库迁移」一节。

### 前端运行要求（`deerfect-web/`）

Node 版本以 `deerfect-web/.nvmrc` 为准（22，本机 nvm 装的是 22.20.0）：vite 7 / jsdom 29 最低要求 20.19，
vitest 5 要求 ≥ 22.12——旧 Node 20.18 跑 `npm test` 会报 `ERR_REQUIRE_ESM`，属于版本不满足而非
代码缺陷。

PWA 只在构建产物里生效：`npm run dev` 不注册 service worker（`vite-plugin-pwa` 的 `devOptions`
默认关闭），本地验证 manifest / SW / 离线门用 `npm run build && npm run preview`。手机端必须
HTTPS 或 localhost 才能注册 SW，局域网 `http://IP:端口` 无法「添加到主屏」。

### 密钥与环境变量

- 本地密钥与远程地址放仓库根 `.env.local`（未纳入版本控制）：`QWEN_API_KEY`、`DEEPSEEK_API_KEY`、
  `NAPCAT_REMOTE_HOST`、`TTS_PYTHON`、`CHANNEL_FEISHU_WS_VOICE_FFMPEG`。
- **邮件**：`MAIL_HOST` / `MAIL_USERNAME` / `MAIL_PASSWORD`（授权码，不是登录密码）/ `MAIL_FROM`
  （可省略，默认等于 `MAIL_USERNAME`）+ `CHANNEL_EMAIL_ENABLED`；步骤见
  [channel-email/README.md](../channel-email/README.md) 的「启用」。
- **飞书推送**：`FEISHU_WEBHOOK_URL` / `FEISHU_WEBHOOK_SECRET` + `CHANNEL_FEISHU_ENABLED`；步骤见
  [channel-feishu/README.md](../channel-feishu/README.md) 的「推送（webhook，M3a）」。
- **飞书对话**：`FEISHU_APP_ID` / `FEISHU_APP_SECRET` / `CHANNEL_FEISHU_WS_ALLOWED_USERS`，见
  [channel-feishu/README.md](../channel-feishu/README.md) 的「接入准备」。
- 邮件与飞书推送默认关闭，关闭时对应渠道不会出现在注册表里。
- 不要把密钥写进 yaml、脚本或提交记录。

### 流水线（CI 与镜像）

[.github/workflows/ci.yml](../.github/workflows/ci.yml) 三个 job，push 到 `master` 与 PR 时触发：

| job       | 内容                                                                                                                                                                                                                                 |
| --------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `backend` | temurin 25 + MySQL 8.4 service；`mvn -B verify`（不依赖 `.mvn/settings.xml`）。上下文测试会跑全量 Flyway 迁移，校验失败即红；`verify` 同时卡覆盖率门禁，日志 `Rule violated` = 跌破模块阈值，报告作为 `jacoco-reports` 产物上传（失败也传） |
| `web`     | Node 22；`npm ci` → `lint`（ESLint flat config）→ `build`（含 vue-tsc）→ `test`（vitest）                                                                                                                                               |
| `e2e`     | Node 22 + Chromium；`test:e2e` + Pages/PWA 构建产物冒烟（mock，无需后端；验静态深链与离线重载，失败传 `playwright-report`）                                                                                                                        |

前端 lint 同样是硬门禁：`npm run lint` 0 error 才放行。规则取向是「只拦会出错或会腐化的」——
8 条纯排版规则（属性换行、自闭合空格等）已关闭，避免每次改动都产生无关 diff。

分支保护（required status checks = `backend` / `web` / `e2e`）需仓库管理员在 GitHub
`Settings → Branches`（或 Gitee 保护分支）勾选，CLI 无法自动配置。

镜像由 [.github/workflows/docker-image.yml](../.github/workflows/docker-image.yml) 在
`docker/`、`deerfect-server/pom.xml` 等路径变化时构建，`master` 推 `sha-<7位>` + `latest`，
`v*` tag 推版本号，PR 只构建不推送；镜像仅 linux/amd64。**拉取、本地构建、tag 长短差异与
GHCR 权限**见 [guides/docker-deploy.md](./guides/docker-deploy.md) 的「镜像来源与版本号」。

### 覆盖率门禁（jacoco）

- 阈值是**行覆盖率（LINE）**，按模块独立设置：父 pom 只出报告、各模块用自己的
  `jacoco.line.minimum` 卡门槛，**数值以各模块 `pom.xml` 为准，本文不维护数字**。
- `report` / `check` 绑在 `verify` 上：只跑 `test` 能过、`verify` 红，就是覆盖率跌破模块阈值——
  报告在 `target/site/jacoco/index.html`，日志会打印实际值与阈值。
- 调整方式与「为什么阈值取实测值下方 2 个点」见
  [guides/functional-testing.md](./guides/functional-testing.md) 的「测试基建约定」；**不要放宽规则本身**。

## 二、本机操作

### 加载 `.env.local`

加载方式按入口分，别记混：

- `scripts/run-wsl.sh`：**会**自动加载（脚本内 `[ -f .env.local ] && . ./.env.local`）。
- `scripts/run-win.bat`：不会（文件注释写明；Windows 侧用 `setx` 写用户环境）。
- 手工 `java -jar` / `mvn spring-boot:run`：不会，先执行：

```bash
set -a; source .env.local; set +a
```

`set -a` 让变量自动 `export` 给子进程，否则只存在于当前 shell，JVM 读不到。长期变量用 `setx`
写入用户环境，或直接在启动命令行前内联。

### Maven 常用命令

```bash
mvn -s .mvn/settings.xml -DskipTests install          # 全量构建
mvn -s .mvn/settings.xml -pl deerfect-server -am test          # 单模块测试（必须 -am）
mvn -s .mvn/settings.xml verify                       # 全量测试 + 覆盖率门禁（提交前跑这个）
mvn -s .mvn/settings.xml -pl deerfect-server spring-boot:run   # 只起服务
mvn -s .mvn/settings.xml -pl <module> -am verify -Djacoco.line.minimum=0.99   # 临时验证会被拦
```

- `-am` 不能省：省略会用本地仓库里旧的 `deerfect-api`，改接口后容易出「明明改了却没生效」。
- 没有 `.mvn/settings.xml` 时直接 `mvn`，其余参数不变。

### 启动服务

启动脚本在 `scripts/` 下（`scripts/run-win.bat` / `scripts/run-wsl.sh`），也可以两步手动启动：

```bash
mvn -s .mvn/settings.xml -DskipTests install       # 首次，或改了模块依赖
java -jar deerfect-server/target/deerfect-server-0.0.1-SNAPSHOT.jar  # 默认 8080
```

- 服务就绪判据：`GET http://localhost:8080/api/harness/agents` 能返回。
- 只改 `channel-*`、server 源码没变时，`package` 可能复用旧 fat jar——加 `clean`
  （见 [design/2026-11-11-feishu-channel-design.md](./design/2026-11-11-feishu-channel-design.md)）。

### 启动 CLI

必须先 `compile` 再 `exec:exec`，否则 `target/classes` 是旧的（曾导致乱码回归）：

```bash
mvn -s .mvn/settings.xml -pl deerfect-cli -Pcli compile exec:exec
```

### 检查依赖服务连通

跑测试 / 起服务前先确认 3306 在监听：

```powershell
Test-NetConnection 127.0.0.1 -Port 3306
```

### 前端开发与验证

```bash
cd deerfect-web
npm install
npm run dev        # 80，proxy 到 8080
npm run typecheck  # vue-tsc --noEmit
npm run build      # typecheck + vite build
npm run build:pages # 子路径产物（base=/Deerfect_harness/，自带 mock，见 .env.pages），供 GitHub Pages 项目页部署
npx vite preview --mode pages  # 本地预览子路径产物，地址带 /Deerfect_harness/
npm test           # vitest run（Node ≥ 22.12）
npx playwright install chromium   # 首次跑 E2E 前装浏览器
npm run test:e2e   # Playwright 开发服务器冒烟（mock 模式，不需要后端）
E2E_BUILD_MODE=pages npx playwright test --config playwright.build.config.ts # 构建并模拟 Pages 404 回退
E2E_BUILD_MODE=pwa npx playwright test --config playwright.build.config.ts   # 启用 SW 构建，验证离线刷新/新开页面
```

本地不想下载 Playwright 的 Chromium 时，可复用系统浏览器（CI 不设该变量，仍用自带 Chromium）：

```powershell
$env:PLAYWRIGHT_CHANNEL='chrome'; npm run test:e2e   # 系统 Chrome；Edge 用 'msedge'
```

### 前端路由与静态托管

- 前端用 History API 路由，地址形如 `/dashboard`、`/chat/trace`、`/knowledge`，**不带 `#`**；
  刷新、直达链接与浏览器前进/后退都按 `pathname` 还原页面，旧 hash 书签（`/#/mcp`）启动时
  会就地改写成 `/mcp`。
- `npm run dev` / `npm run preview` 自带 SPA fallback；用 nginx 等静态服务器托管 `dist/` 时，
  必须把未命中的路径回落到 `index.html`，否则直达或刷新 `/knowledge` 会 404：

  ```nginx
  location / {
    try_files $uri $uri/ /index.html;
  }
  ```

- `/api` 仍由反向代理转给 8080，与前端路由不冲突；`/assets`、`/icons` 等静态文件正常直出。

### 渠道联调（只列本机要做的事）

| 渠道 | 本机动作 | 完整步骤 |
| --- | --- | --- |
| 邮件 | `.env.local` 配 `MAIL_*` 并设 `CHANNEL_EMAIL_ENABLED=true`；发一封到手机邮箱，确认不在垃圾箱 | [channel-email/README.md](../channel-email/README.md)「启用」 |
| 飞书推送 | 群自定义机器人 webhook + 签名密钥，`CHANNEL_FEISHU_ENABLED=true`；`notify_channel` 写 `feishu:main` | [channel-feishu/README.md](../channel-feishu/README.md)「推送（webhook，M3a）」 |
| 飞书对话 | 自建应用开长连接与三项权限，`CHANNEL_FEISHU_WS_ENABLED=true` + App 凭据 | [channel-feishu/README.md](../channel-feishu/README.md)「接入准备」 |
| QQ | 本机直连 `127.0.0.1:3000`；NapCat 在远程 Docker 时走 SSH 双隧道 | [channel-qq/README.md](../channel-qq/README.md)「远程联调（SSH 双隧道）」 |

邮件渠道的状态来自「最近一次投递结果」：从未投递时显示「未知（尚无投递记录）」，这是预期行为。

## 三、FAQ（本机实测）

- **GitHub 推送经常 408 超时 / TLS 中断**：默认上游是 `origin/master`（GitHub），Gitee 是备用远端；GitHub 失败不要反复重试，可先推 Gitee 或改用 SSH。
- **8080 被占用**：`netstat -aon | findstr :8080` 找 PID，`taskkill /F /PID <pid>` 结束；没有脚本会自动清理。
- **只改 `channel-*` 后跑起来还是旧代码**：`deerfect-server` 源码没变时 `mvn -pl deerfect-server -am package`
  会复用旧 fat jar，加 `clean`。
- **MySQL 反复退出**：确认用的是 `--console` 前台模式；后台方式在无服务权限的机器上容易被系统回收。
- **改了接口却编译不过**：先确认命令带了 `-am`（`-s .mvn/settings.xml` 只有本机有，不是必需）。
- **`mvn verify` 里 cli 测试卡几十秒**：JLine 探测终端的副作用，已修（详见
  [design/2026-11-11-cli-terminal-probe.md](./design/2026-11-11-cli-terminal-probe.md)）；
  新环境若复现，先看 `deerfect-cli/target/surefire-reports` 里 `TerminalInputTest` 的 `time`。
- **`nvm use` 报权限不足**：nvm-windows 切换版本需要管理员权限；切换失败时 `D:\nodejs` 链接
  可能被移除，重新 `nvm use` 即可重建。
