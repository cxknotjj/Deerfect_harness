# 本地开发环境（dev）

> 只记录三类知识：**环境事实**（这台机器与这个项目是什么）、**本机操作**（在本机怎么做）、  
> **FAQ**（实测踩过的坑）。全文按这三类分节，不设第四类标题。
>
> 不重复 [README](../README.md) 的快速开始；首次部署见 [guides/setup.md](./guides/setup.md)，  
> 运维口径见 [ops.md](./ops.md)。设计决策见 [design/](./design/)  
> 渠道 how-to 见 [channel-feishu/README.md](../channel-feishu/README.md)、  
> [channel-qq/README.md](../channel-qq/README.md)。
>
> 改端口、改启动方式、换依赖服务时，同一次提交内更新本文件；版本号与路径每季度核对一次。
>
> 最后按代码核对：2026-11-11

## 一、环境事实

### 工具链（Windows 侧实测）

| 工具         | 版本                                   | 说明                             |
| ---------- | ------------------------------------ | ------------------------------ |
| JDK        | 25（Microsoft build，`D:\env\jdk-25`）  | `JAVA_HOME` 必须指向它              |
| Maven      | 3.9.4（`D:\apache-maven-3.9.4`）       | 仓库没有 `mvnw`，直接用 `mvn`          |
| Node / npm | v22.20.0 / 10.9.3                    | 只给 `web/` 用；经 nvm 安装，见「前端运行要求」 |
| MySQL      | 8.4.5（`D:\mysql\mysql-8.4.5-winx64`） | 见「端口与依赖服务」                     |

`.mvn/settings.xml` 是本机文件，**不在仓库里**（`.gitignore` 排除了 `.mvn/`）：它把本地仓库  
指向 `.mvn-repo/`（仓库内）并配置阿里云镜像。干净 clone、CI 与镜像构建都没有它——直接 `mvn`  
会走默认 `~/.m2` 和公网 central，能构建但慢，不是错误。下文命令按本机有该文件来写。

### 覆盖率门禁（jacoco）

- 阈值是**行覆盖率（LINE）**，按模块独立设置：父 pom 的 `jacoco.line.minimum` 默认 `0.00`（只出报告），  
  各模块在自己的 `<properties>` 覆盖为「实测值下方约 2 个点」。当前：server 0.79 / channel-qq 0.75 /  
  channel-feishu 0.74 / channel-email 0.61 / wms-mcp-adapter 0.73 / harness-api 0.61 / cli 0.21。
- `report` / `check` 绑在 `verify` 上：只跑 `test` 能过、`verify` 红，就是覆盖率跌破模块阈值——  
  报告在 `target/site/jacoco/index.html`，日志会打印实际值与阈值。
- 调整方式：先跑 `verify` 看实际值，确认下降合理后再改模块属性；**不要放宽规则本身**。

### 端口与依赖服务

| 组件            | 地址                            | 是否必需   | 说明                                                                                                  |
| ------------- | ----------------------------- | ------ | --------------------------------------------------------------------------------------------------- |
| server        | `8080`                        | 必需     | REST + SSE；MCP 端点在同端口 `/mcp`                                                                        |
| Web 开发服务器     | `5173`                        | 只在调前端时 | vite，proxy 到 8080                                                                                   |
| MySQL         | `127.0.0.1:3306`，库 `harness`  | 必需     | 配置见 `application-datasource.yaml`                                                                   |
| PgVector      | `127.0.0.1:5432`，库 `postgres` | 可选     | 仅知识库（RAG）；未启用时 `/api/knowledge*` 返回 503                                                             |
| Docker daemon | `127.0.0.1:2375`              | 可选     | 沙箱工具；**必须写 `127.0.0.1`，不要写 `localhost`**（IPv6 解析会超时）                                                |
| NapCat        | `127.0.0.1:3000`              | 可选     | QQ 渠道；远程联调见「QQ 远程 NapCat 联调」；`napcat.enabled=false` 时整链不装配                                          |
| Edge TTS      | Python 解释器（`TTS_PYTHON`）      | 可选     | 语音合成（QQ / 飞书）；需 `pip install edge-tts`。飞书还要 ffmpeg 转 opus（`CHANNEL_FEISHU_WS_VOICE_FFMPEG`），缺了只退回文字 |

### 数据库迁移（Flyway）

Flyway 随 server 启动自动执行 `server/src/main/resources/db/migration/` 下的脚本；
新增迁移取**目录里当前最大版本号 +1**，已应用的脚本不可再改。

这里不记录「当前版本是多少」——数字每次加迁移都会变，权威来源就是那个目录。
各版本做了什么见 [project-structure.md](./project-structure.md) 的「数据库迁移」一节。

### 前端运行要求（`web/`）

Node 版本以 `web/.nvmrc` 为准（22，本机 nvm 装的是 22.20.0）：vite 7 / jsdom 29 最低要求 20.19，  
vitest 5 要求 ≥ 22.12——旧 Node 20.18 跑 `npm test` 会报 `ERR_REQUIRE_ESM`，属于版本不满足而非  
代码缺陷。

PWA 只在构建产物里生效：`npm run dev` 不注册 service worker（`vite-plugin-pwa` 的 `devOptions`  
默认关闭），本地验证 manifest / SW / 离线门用 `npm run build && npm run preview`。手机端必须  
HTTPS 或 localhost 才能注册 SW，局域网 `http://IP:端口` 无法「添加到主屏」。

### 密钥与环境变量

- 本地密钥与远程地址放仓库根 `.env.local`（未纳入版本控制）：`QWEN_API_KEY`、`DEEPSEEK_API_KEY`、  
  `NAPCAT_REMOTE_HOST`、`TTS_PYTHON`、`CHANNEL_FEISHU_WS_VOICE_FFMPEG`。
- 邮件渠道用 `MAIL_HOST`（QQ 邮箱为 `smtp.qq.com`）、`MAIL_USERNAME`（完整发件地址）、  
  `MAIL_PASSWORD`（**授权码，不是登录密码**）、`MAIL_FROM`（可省略，默认等于 `MAIL_USERNAME`），  
  开关 `CHANNEL_EMAIL_ENABLED`；飞书推送渠道（webhook）用 `FEISHU_WEBHOOK_URL` /  
  `FEISHU_WEBHOOK_SECRET` + `CHANNEL_FEISHU_ENABLED`。  
  两者默认关闭，关闭时对应渠道不会出现在注册表里。
- 飞书**对话渠道**（长连接）的 `FEISHU_APP_ID` / `FEISHU_APP_SECRET` / `CHANNEL_FEISHU_WS_ALLOWED_USERS`  
  见 [channel-feishu/README.md](../channel-feishu/README.md) 的「接入准备」。
- 不要把密钥写进 yaml、脚本或提交记录。

### 流水线（CI 与镜像）

[.github/workflows/ci.yml](../.github/workflows/ci.yml) 三个 job，push 到 `master` 与 PR 时触发：

| job       | 内容                                                                                                                                                                                                                                 |
| --------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `backend` | temurin 25 + MySQL 8.4 service；`mvn -Dmaven.repo.local=$HOME/.m2/repository -B verify`（不依赖 `.mvn/settings.xml`）。上下文测试启动时会跑全量 Flyway 迁移，迁移校验失败即红；`verify` 同时跑覆盖率门禁，日志出现 `Rule violated` 即覆盖率跌破模块阈值，报告作为 `jacoco-reports` 产物上传（失败也传） |
| `web`     | Node 22；`npm ci` → `npm run lint`（ESLint flat config）→ `npm run build`（含 vue-tsc 类型检查）→ `npm test`（vitest）                                                                                                                         |
| `e2e`     | Node 22 + Chromium；`npm run test:e2e`（mock 模式，不需要后端与数据库，失败上传 `playwright-report`）                                                                                                                                                  |

前端 lint 同样是硬门禁：`npm run lint` 0 error 才放行。规则取向是「只拦会出错或会腐化的」——  
8 条纯排版规则（属性换行、自闭合空格等）已关闭，避免每次改动都产生无关 diff。

分支保护（required status checks = `backend` / `web` / `e2e`）需仓库管理员在 GitHub  
`Settings → Branches`（或 Gitee 保护分支）勾选，CLI 无法自动配置。

镜像构建 [docker-image.yml](../.github/workflows/docker-image.yml)：仅当 docker/server/pom 等  
路径有变化时触发。`master` 推送 `ghcr.io/cxknotjj/deerfect-harness:sha-<7位>` 与 `latest`，  
`v*` tag 推送同名版本；PR 只构建不推送。镜像仅 linux/amd64，使用 buildx + gha 层缓存。  
镜像名固定 `deerfect-harness`（展示名 DeerFect-harness）：Docker/GHCR 仓库名强制小写，  
所以不能用仓库名 `Deerfect_harness` 原样命名。

GHCR 包首次推送后默认私有；部署机不登录也能拉取时，在仓库 Packages 设置里改为 Public。  
私有包拉取需 `docker login ghcr.io`（用户名 = GitHub 账号，密码 = 勾选 `read:packages` 的 PAT）。

## 二、本机操作

### 加载 `.env.local`

启动脚本已移除，手工 `java -jar` / `mvn spring-boot:run` **不会**自动加载 `.env.local`：

```bash
set -a; source .env.local; set +a
```

`set -a` 让变量自动 `export` 给子进程，否则只存在于当前 shell，JVM 读不到。长期变量用 `setx`  
写入用户环境，或直接在启动命令行前内联。

### Maven 常用命令

```bash
mvn -s .mvn/settings.xml -DskipTests install          # 全量构建
mvn -s .mvn/settings.xml -pl server -am test          # 单模块测试（必须 -am）
mvn -s .mvn/settings.xml verify                       # 全量测试 + 覆盖率门禁（提交前跑这个）
mvn -s .mvn/settings.xml -pl server spring-boot:run   # 只起服务
mvn -s .mvn/settings.xml -pl <module> -am verify -Djacoco.line.minimum=0.99   # 临时验证会被拦
```

- `-am` 不能省：省略会用本地仓库里旧的 `harness-api`，改接口后容易出「明明改了却没生效」。
- 没有 `.mvn/settings.xml` 时直接 `mvn`，其余参数不变。

### 启动服务

历史脚本 `run-win.bat` / `run-wsl.sh` 已移除，现在是两步：

```bash
mvn -s .mvn/settings.xml -DskipTests install       # 首次，或改了模块依赖
java -jar server/target/server-0.0.1-SNAPSHOT.jar  # 默认 8080
```

- 服务就绪判据：`GET http://localhost:8080/api/harness/agents` 能返回。
- 只改 `channel-*`、server 源码没变时，`package` 可能复用旧 fat jar——加 `clean`  
  （见 [design/2026-11-11-feishu-channel-design.md](./design/2026-11-11-feishu-channel-design.md)）。

### 启动 CLI

必须先 `compile` 再 `exec:exec`，否则 `target/classes` 是旧的（曾导致乱码回归）：

```bash
mvn -s .mvn/settings.xml -pl cli -Pcli compile exec:exec
```

### 检查依赖服务连通

跑测试 / 起服务前先确认 3306 在监听：

```powershell
Test-NetConnection 127.0.0.1 -Port 3306
```

### 前端开发与验证

```bash
cd web
npm install
npm run dev        # 5173，proxy 到 8080
npm run typecheck  # vue-tsc --noEmit
npm run build      # typecheck + vite build
npm test           # vitest run（Node ≥ 22.12）
npx playwright install chromium   # 首次跑 E2E 前装浏览器
npm run test:e2e   # Playwright 冒烟 5 条，mock 模式，不需要后端
```

本地不想下载 Playwright 的 Chromium 时，可复用系统浏览器（CI 不设该变量，仍用自带 Chromium）：

```powershell
$env:PLAYWRIGHT_CHANNEL='chrome'; npm run test:e2e   # 系统 Chrome；Edge 用 'msedge'
```

### 启用邮件推送渠道

五个环境变量（见「密钥与环境变量」），配置在 `application-channel.yaml` 的 `channel.email.*`，  
占位符已接好，**不用改 yaml**；关闭时 `server` 的依赖树里没有 mail starter（渠道模块是 runtime  
依赖）。首次启用按这个顺序检查，别跳过第 3 步：

1. `MAIL_FROM` 不设时自动等于 `MAIL_USERNAME`；若单独设置，两者必须一致（多数服务商强制要求，不一致会被拒信）
2. 发件人加入服务商白名单；主题避免「中奖 / 免费」这类词
3. **发一封测试邮件到你手机上的邮箱客户端，确认它不在垃圾箱**——「邮件能发出去」和  
   「你会看到它」是两件事，后者才是告警的意义
4. 打开监控页确认渠道状态：邮件渠道的状态来自「最近一次投递结果」，从未投递时会显示  
   「未知（尚无投递记录）」，这是预期行为，不是故障

### 启用飞书推送渠道（webhook）

单向推送（M3a），不是对话入口——对话渠道见 [channel-feishu/README.md](../channel-feishu/README.md)。

1. 飞书里建一个只有自己的群 → 群设置 → 群机器人 → 添加「自定义机器人」
2. 复制 webhook 地址，开启**签名校验**并复制密钥（`secret`）
3. `channel.feishu.enabled: true`，`notify_channel` 写 `feishu:main`——**别名**，不是 URL；  
   URL 与 secret 是凭证，只放环境变量，不进业务表也不进 git
4. 缺 secret 时应用会**拒绝启动**：不签名的 webhook 等于对任何拿到 URL 的人开放

M3a 不能接收消息、不能当对话入口，也没有 `message_id`（查不了已读、撤不回）。  
请求体上限 20 KB，超长正文由投递层自动改走邮件（不截断）。

### QQ 远程 NapCat 联调

NapCat 跑在远程 Docker 时，用两条隧道打通「本机 server ↔ 远程 NapCat」：

```powershell
# 终端 A（本机，保持不关）：拉回 NapCat API/WebUI，并把远程上报反向隧道回本机 8080
# 主机地址只存 .env.local（已 gitignore），文档与提交里不出现真实 IP
$env:NAPCAT_REMOTE_HOST = (Select-String -Path .env.local -Pattern '^NAPCAT_REMOTE_HOST=').Line -replace '^NAPCAT_REMOTE_HOST=',''
ssh -i "C:\Users\23220\id_remote_ali_service" -N -L 3000:127.0.0.1:3000 -L 6099:127.0.0.1:6099 -R 8080:127.0.0.1:8080 "root@$env:NAPCAT_REMOTE_HOST"
```

```bash
# 终端 B（ssh 登录远程主机后执行，保持常驻）：把 Docker 网桥网关的 8080 转到反向隧道落点
socat TCP-LISTEN:8080,bind=172.17.0.1,fork,reuseaddr TCP:127.0.0.1:8080
```

- `NAPCAT_REMOTE_HOST`：远程 NapCat 主机地址，只写仓库根 `.env.local`（已 gitignore），文档/提交里用环境变量引用，不落真实 IP。


- `-L 3000` / `-L 6099`：本机访问 `127.0.0.1:3000`（NapCat HTTP API）与 `127.0.0.1:6099`（WebUI）实际连到远程；`application-qq.yaml` 的默认地址无需改。
- `-R 8080`：远程 `127.0.0.1:8080` 回连本机 harness；NapCat 在容器内只能访问宿主机 Docker 网桥网关，故用 socat 监听 `172.17.0.1:8080` 再转发到反向隧道落点。
- NapCat 的 OneBot HTTP 上报地址填 `http://172.17.0.1:8080/onebot/event`；本机 harness 需先启动（8080 在监听），否则上报失败。
- `172.17.0.1` 是默认 bridge 网关；使用自定义 Docker 网络时按 `docker network inspect` 实际网关调整。

### 拉取与本地构建镜像

部署机用 CI 镜像（compose 已支持 `IMAGE_REPO`，不触发本地构建）：

```bash
IMAGE_REPO=ghcr.io/cxknotjj/deerfect-harness docker compose -f docker/docker-compose.yml pull app
IMAGE_REPO=ghcr.io/cxknotjj/deerfect-harness IMAGE_TAG=sha-xxxxxxx \
  docker compose -f docker/docker-compose.yml up -d --no-build
```

```

本地仍可用 `docker/build-image.ps1` / `.sh` 构建（tag 为 `sha-<8位>`）；CI 的 tag 是 7 位短 sha，
回滚以实际 tag 为准。

## 三、FAQ（本机实测）

- **GitHub 推送经常 408 超时**：默认上游是 `origin/master`（GitHub），Gitee 是备用远端；GitHub 失败不要反复重试，可先推 Gitee 或改用 SSH。
|  |  |
| --- | --- |
- **8080 被占用**：`netstat -aon | findstr :8080` 找 PID，`taskkill /F /PID <pid>` 结束；没有脚本会自动清理。
- **只改 `channel-*` 后跑起来还是旧代码**：`server` 源码没变时 `mvn -pl server -am package`
  会复用旧 fat jar，加 `clean`。
- **MySQL 反复退出**：确认用的是 `--console` 前台模式；后台方式在无服务权限的机器上容易被系统回收。
- **改了接口却编译不过**：先确认命令带了 `-am`（`-s .mvn/settings.xml` 只有本机有，不是必需）。
- **`mvn verify` 里 cli 测试卡几十秒**：JLine 探测终端的副作用，已修（详见
  [design/2026-11-11-cli-terminal-probe.md](./design/2026-11-11-cli-terminal-probe.md)）；
  新环境若复现，先看 `cli/target/surefire-reports` 里 `TerminalInputTest` 的 `time`。
- **`nvm use` 报权限不足**：nvm-windows 切换版本需要管理员权限；切换失败时 `D:\nodejs` 链接
  可能被移除，重新 `nvm use` 即可重建。
```
