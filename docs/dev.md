# 本地开发环境（dev）

> 只记录**本机实测的现状与坑**，不重复 [README](../README.md) 的快速开始；首次部署细节见 [guides/setup.md](./guides/setup.md)。
>
> 改端口、改启动方式、换依赖服务时，同一次提交内更新本文件。
>
> 最后按代码核对：2026-11-11

## 1. 工具链（Windows 侧实测）

| 工具         | 版本                                   | 说明                          |
| ---------- | ------------------------------------ | --------------------------- |
| JDK        | 25（Microsoft build，`D:\env\jdk-25`）  | `JAVA_HOME` 必须指向它           |
| Maven      | 3.9.4（`D:\apache-maven-3.9.4`）       | 仓库没有 `mvnw`，直接用 `mvn`       |
| Node / npm | v22.20.0 / 10.9.3                    | 只给 `web/` 用；经 nvm 安装，见第 5 节 |
| MySQL      | 8.4.5（`D:\mysql\mysql-8.4.5-winx64`） | 详见第 4 节                     |

**本机命令建议带 `-s .mvn/settings.xml`**：该文件把本地仓库指向 `.mvn-repo/`（仓库内），并配置阿里云镜像。它**不在仓库里**（`.gitignore` 排除了 `.mvn/`），干净 clone、CI 与镜像构建都没有这个文件——直接 `mvn` 会走默认 `~/.m2` 和公网 central，能构建但慢，不是错误。下面的命令按本机有该文件来写。

```bash
mvn -s .mvn/settings.xml -DskipTests install          # 全量构建
mvn -s .mvn/settings.xml -pl server -am test          # 单模块测试（必须 -am）
mvn -s .mvn/settings.xml verify                       # 全量测试 + 覆盖率门禁（提交前跑这个）
mvn -s .mvn/settings.xml -pl server spring-boot:run   # 只起服务
```

- `-am` 不能省：省略会用本地仓库里旧的 `harness-api`，改接口后容易出「明明改了却没生效」。
- 全量测试：`mvn -s .mvn/settings.xml verify`；没有 settings 文件时直接 `mvn verify`。
- **`test` 不跑覆盖率门禁**：jacoco 的 `report` / `check` 绑在 `verify` 上。只跑 `test` 能过、`verify` 红，
  就是覆盖率跌破了模块阈值——报告在 `target/site/jacoco/index.html`，日志会打印实际值与阈值。

### 覆盖率门禁（jacoco）

- 阈值是**行覆盖率（LINE）**，按模块独立设置：父 pom 的 `jacoco.line.minimum` 默认 `0.00`（只出报告），
  各模块在自己的 `<properties>` 覆盖为「实测值下方约 2 个点」。当前：server 0.79 / channel-qq 0.75 /
  channel-feishu 0.74 / channel-email 0.61 / wms-mcp-adapter 0.73 / harness-api 0.61 / cli 0.21。
- 调整方式：先跑 `mvn verify` 看报告里的实际值，确认下降合理后再改模块属性；**不要放宽规则本身**。
- 临时验证某模块会不会被拦：`mvn -pl <module> -am verify -Djacoco.line.minimum=0.99`（命令行属性优先级最高）。

## 2. 启动

历史上一键脚本 `run-win.bat` / `run-wsl.sh` 已移除，现在是两步：

```bash
mvn -s .mvn/settings.xml -DskipTests install       # 首次，或改了模块依赖
java -jar server/target/server-0.0.1-SNAPSHOT.jar  # 默认 8080
```

- 服务就绪判据：`GET http://localhost:8080/api/harness/agents` 能返回。
- 只改 `channel-*`、server 源码没变时，`package` 可能复用旧 fat jar——加 `clean`
  （详见「飞书长连接」一节的坑）。
- CLI 必须先 `compile` 再 `exec:exec`，否则 `target/classes` 是旧的（曾导致乱码回归）：

```bash
mvn -s .mvn/settings.xml -pl cli -Pcli compile exec:exec
```

## 3. 端口与依赖服务

| 组件            | 地址                            | 是否必需   | 说明                                                                     |
| ------------- | ----------------------------- | ------ | ---------------------------------------------------------------------- |
| server        | `8080`                        | 必需     | REST + SSE；MCP 端点在同端口 `/mcp`                                           |
| Web 开发服务器     | `5173`                        | 只在调前端时 | vite，proxy 到 8080                                                      |
| MySQL         | `127.0.0.1:3306`，库 `harness`  | 必需     | 配置见 `application-datasource.yaml`                                      |
| PgVector      | `127.0.0.1:5432`，库 `postgres` | 可选     | 仅知识库（RAG）；未启用时 `/api/knowledge*` 返回 503                                |
| Docker daemon | `127.0.0.1:2375`              | 可选     | 沙箱工具；**必须写** **`127.0.0.1`** **不要写** **`localhost`**（IPv6 解析会超时）       |
| NapCat        | `127.0.0.1:3000`              | 可选     | QQ 渠道；远程 NapCat 联调见 3.1；`napcat.enabled=false` 时整链不装配                  |
| Edge TTS      | Python 解释器（`TTS_PYTHON`）      | 可选     | 语音合成（QQ / 飞书）；需 `pip install edge-tts`。飞书还要 ffmpeg 转 opus（`CHANNEL_FEISHU_WS_VOICE_FFMPEG`），缺了只退回文字 |

### 3.1 QQ 机器人联调前提（远程 NapCat）

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

## 4. 数据库

- 跑测试/起服务前先确认 3306 在监听：

```powershell
Test-NetConnection 127.0.0.1 -Port 3306
```

- Flyway 随 server 启动自动迁移，**当前最大版本 `V19`**（`V1` + V2–V19，共 19 个脚本）；新增从 `V20` 编号，不改已发布脚本。清单以 `server/src/main/resources/db/migration/` 为准——`project-structure.md` 里的迁移清单只到 V11，已过时。
- **迁移压平（2026-10-30，历史操作）**：原 V1\~V43（42 个文件）已合并为单个 `V1__init_schema.sql`
  （最终 schema + 迁移种子数据）；之后从 V2 重新编号。V7–V13 是定时任务 / 推送幂等 / Goal 重投 / 画像增量 / 归档跨会话索引 / 会话滚动摘要 / 显式记忆，V14 之后按功能继续追加。
- **存量库升级（仅压平当时适用，勿再照做）**：当时先 `DROP TABLE flyway_schema_history;` 再启动，
  让 `baseline-on-migrate` 以 V1 为基线跳过 V1。现在历史表已在正常使用，**再删表会让 Flyway
  对已有库重跑迁移**，切勿执行。若因修改迁移脚本导致校验和不匹配，按
  [project-structure.md](./project-structure.md) 的做法 `flyway repair` 校正 checksum。

## 5. 前端（`web/`）

Node 版本用 `web/.nvmrc`（22，本机 nvm 装的是 22.20.0）：vite 7 / jsdom 29 最低要求 20.19，
vitest 5 要求 ≥ 22.12——旧 Node 20.18 跑 `npm test` 会报 `ERR_REQUIRE_ESM`，属于版本不满足而非
代码缺陷。nvm-windows 的 `nvm use` 需要管理员权限；切换失败时 `D:\nodejs` 链接可能被移除，
重新 `nvm use` 即可重建。

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

PWA（P3-1）只在构建产物里生效：`npm run dev` 不注册 service worker（`vite-plugin-pwa` 的
`devOptions` 默认关闭），本地验证 manifest / SW / 离线门用 `npm run build && npm run preview`。
手机端必须 HTTPS 或 localhost 才能注册 SW，局域网 `http://IP:端口` 无法「添加到主屏」。

### 请求超时与错误提示

- 普通 REST 统一走 `fetchWithTimeout`（默认 30s，`VITE_API_TIMEOUT_MS` 覆盖）：后端挂起时抛
  `请求超时（30s）：/api/...` 并带原始 `cause`，不会无限等待。`login` 虽然单独 `fetch`
  （为了不触发 401 全局钩子），也走同一条超时封装。
- 三处异常入口收敛到 `web/src/composables/globalError.ts`：`app.config.errorHandler`
  （根组件/渲染）、`unhandledrejection`（异步未捕获）、根组件 `onErrorCaptured`（子树，
  返回 false 阻止向上）。顶部红色横幅显示「类型: 首行消息」，8s 自动消失、可手动关闭；
  完整堆栈仍打 console。有横幅时页面其余部分照常可用，不再整页白屏。

## 6. 密钥与环境变量

- 本地密钥与远程地址放仓库根 `.env.local`（未纳入版本控制），如 `QWEN_API_KEY`、`DEEPSEEK_API_KEY`、`NAPCAT_REMOTE_HOST`（远程 NapCat 服务器地址，见 3.1）、`TTS_PYTHON`（已装 edge-tts 的 Python 解释器路径）。
- 启动脚本已移除，手工 `java -jar` / `mvn spring-boot:run` **不会**自动加载 `.env.local`；用下面的 `set -a; source`，或把长期变量 `setx` 到用户环境。
- 手工 `mvn` / `java` 启动时先 `set -a; source .env.local; set +a`：`set -a` 让变量自动 `export` 给子进程，否则只存在于当前 shell，JVM 读不到。
- 不要把密钥写进 yaml、脚本或提交记录。

**邮件渠道（可选）**：五个环境变量——`CHANNEL_EMAIL_ENABLED=true`、`MAIL_HOST`（QQ 邮箱为 `smtp.qq.com`）、`MAIL_USERNAME`（完整发件地址）、`MAIL_PASSWORD`（**授权码，不是登录密码**）、`MAIL_FROM`（可省略，默认等于 `MAIL_USERNAME`）。
配置在 `application-channel.yaml` 的 `channel.email.*`，占位符已接好，**不用改 yaml**；关闭时
`server` 的依赖树里没有 mail starter（渠道模块是 runtime 依赖），注册表也不会出现 `email` 渠道。

首次启用请按这个顺序检查，别跳过第 3 步：

1. `MAIL_FROM` 不设时自动等于 `MAIL_USERNAME`；若单独设置，两者必须一致（多数服务商强制要求，不一致会被拒信）
2. 发件人加入服务商白名单；主题避免「中奖 / 免费」这类词
3. **发一封测试邮件到你手机上的邮箱客户端，确认它不在垃圾箱**——「邮件能发出去」和
   「你会看到它」是两件事，后者才是告警的意义
4. 打开监控页确认渠道状态：邮件渠道的状态来自「最近一次投递结果」，从未投递时会显示
   「未知（尚无投递记录）」，这是预期行为，不是故障

**飞书渠道（可选）**：`FEISHU_WEBHOOK_URL`、`FEISHU_WEBHOOK_SECRET`。配置在
`application-channel.yaml` 的 `channel.feishu.*`，`enabled: false` 默认关闭；关闭时注册表里
不会出现 `feishu` 渠道。启用步骤：

1. 飞书里建一个只有自己的群 → 群设置 → 群机器人 → 添加「自定义机器人」
2. 复制 webhook 地址，开启**签名校验**并复制密钥（`secret`）
3. `channel.feishu.enabled: true`，`notify_channel` 写 `feishu:main`——**别名**，不是 URL；
   URL 与 secret 是凭证，只放环境变量，不进业务表也不进 git
4. 缺 secret 时应用会**拒绝启动**：不签名的 webhook 等于对任何拿到 URL 的人开放

M3a 是单向推送：不能接收消息、不能当对话入口，也没有 `message_id`（查不了已读、撤不回）。
请求体上限 20 KB，超长正文由投递层自动改走邮件（不截断）。

## 7. 已知问题（本机实测）

- **GitHub 推送经常 408 超时**：默认上游是 `origin/master`（GitHub），Gitee 是备用远端；GitHub 失败不要反复重试，可先推 Gitee 或改用 SSH。
- **8080 被占用**：`netstat -aon | findstr :8080` 找 PID，`taskkill /F /PID <pid>` 结束；没有脚本会自动清理。
- **MySQL 反复退出**：确认用的是 `--console` 前台模式；后台方式在无服务权限的机器上容易被系统回收。
- **改了接口却编译不过**：先确认命令带了 `-am`（`-s .mvn/settings.xml` 只有本机有，不是必需）。
- **`mvn verify` 里 cli 测试卡几十秒**：JLine 探测终端的副作用，已修（详见文末
  「cli 终端探测」）；新环境若复现，先看 `cli/target/surefire-reports` 里
  `TerminalInputTest` 的 `time`。

## 8. CI 与镜像发布（GitHub Actions）

[.github/workflows/ci.yml](../.github/workflows/ci.yml) 三个 job，push 到 `master` 与 PR 时触发：

| job       | 内容                                                                                                                                          |
| --------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| `backend` | temurin 25 + MySQL 8.4 service；`mvn -Dmaven.repo.local=$HOME/.m2/repository -B verify`（不依赖 `.mvn/settings.xml`）。上下文测试启动时会跑全量 Flyway 迁移，迁移校验失败即红；`verify` 同时跑覆盖率门禁，日志出现 `Rule violated` 即覆盖率跌破模块阈值，报告作为 `jacoco-reports` 产物上传（失败也传） |
| `web`     | Node 22；`npm ci` → `npm run lint`（ESLint flat config）→ `npm run build`（含 vue-tsc 类型检查）→ `npm test`（vitest）                                      |
| `e2e`     | Node 22 + Chromium；`npm run test:e2e`（mock 模式，不需要后端与数据库，失败上传 `playwright-report`）                                                           |

前端 lint 同样是硬门禁：`npm run lint` 0 error 才放行。规则取向是「只拦会出错或会腐化的」——
8 条纯排版规则（属性换行、自闭合空格等）已关闭，避免每次改动都产生无关 diff。

分支保护（required status checks = `backend` / `web` / `e2e`）需仓库管理员在 GitHub
`Settings → Branches`（或 Gitee 保护分支）勾选，CLI 无法自动配置。

镜像构建 [docker-image.yml](../.github/workflows/docker-image.yml)：仅当 docker/server/pom 等
路径有变化时触发。`master` 推送 `ghcr.io/cxknotjj/deerfect-harness:sha-<7位>` 与 `latest`，
`v*` tag 推送同名版本；PR 只构建不推送。镜像仅 linux/amd64，使用 buildx + gha 层缓存。
镜像名固定 `deerfect-harness`（展示名 DeerFect-harness）：Docker/GHCR 仓库名强制小写，
所以不能用仓库名 `Deerfect_harness` 原样命名。

- GHCR 包首次推送后默认私有；部署机不登录也能拉取时，在仓库 Packages 设置里改为 Public。
- 私有包拉取需 `docker login ghcr.io`（用户名 = GitHub 账号，密码 = 勾选 `read:packages` 的 PAT）。
- 部署机使用 CI 镜像（compose 已支持 `IMAGE_REPO`，不触发本地构建）：

```bash
IMAGE_REPO=ghcr.io/cxknotjj/deerfect-harness docker compose -f docker/docker-compose.yml pull app
IMAGE_REPO=ghcr.io/cxknotjj/deerfect-harness IMAGE_TAG=sha-xxxxxxx \
  docker compose -f docker/docker-compose.yml up -d --no-build
```

本地仍可用 `docker/build-image.ps1` / `.sh` 构建（tag 为 `sha-<8位>`）；CI 的 tag 是 7 位短 sha，
回滚以实际 tag 为准。

## 9. 运行观测（Actuator / 指标 / 日志）

端点（只暴露这三类，`env` / `heapdump` 等不开放）：

- `GET /actuator/health`、`/actuator/health/liveness`、`/actuator/health/readiness`
- `GET /actuator/prometheus`（Prometheus 抓取格式）

指标（`HarnessMetrics` 埋点，路径判定/LLM 台账/流控/编排降级四个位置）：

| 指标                                | 类型      | 说明 / PromQL 示例                                                                                            |
| --------------------------------- | ------- | --------------------------------------------------------------------------------------------------------- |
| `harness_sse_active`              | gauge   | 活跃 SSE 连接数                                                                                                |
| `harness_llm_first_token_seconds` | timer   | 首 token 延迟。P95：`histogram_quantile(0.95, sum by (le) (rate(harness_llm_first_token_seconds_bucket[5m])))` |
| `harness_llm_duration_seconds`    | timer   | 单次调用耗时；route-judge P95 取 `{agent="route-judge"}`                                                          |
| `harness_llm_calls_total`         | counter | 按 `status=ok/error`；错误率 = `sum(rate(...{status="error"}[5m])) / sum(rate(...[5m]))`                       |
| `harness_graph_fallbacks_total`   | counter | `stage=to_agentic`（编排失败降级单 Agent）/ `digest`（聚合失败降级汇总）                                                     |
| `harness_route_decisions_total`   | counter | 按 `path=graph/agentic`；Graph 降级率 = `fallbacks{stage="to_agentic"}` 速率 / `path="graph"` 速率                 |
| `harness_llm_tokens_total`        | counter | 按 `kind=prompt/completion/total`、`model`；每小时用量 `increase(harness_llm_tokens_total{kind="total"}[1h])`     |

成本由 token 用量 × 供应商单价换算（单价随供应商调整，不在代码里固化，避免过期价格变成伪指标）。

### 会话上下文构成快照

- `GET /api/monitor/context?sessionId=...`：返回该会话最近一次真实 LLM 请求发往模型前的输入构成（system 分段 / RAG / 工具 Schema / 历史 user / 历史 assistant / 当前 user）；无快照返回 404。
- 快照为进程内内存数据：同会话覆盖最近一份，TTL 30 分钟、最多 200 个会话，重启清空；接口只读快照，不重新组装 prompt、不触发 RAG、不访问数据库。
- 估算口径统一为 `TokenEstimator`，占比之和为 100%；ReAct 轮内动态追加的工具结果不计入（属于 `tool_call_log`），多 Agent 编排只保留最近一次调用的快照并标注实际 agent 名。
- Web 监控页「活跃会话」的「上下文」按钮调用该接口，展示堆叠占比条与明细表。

日志：控制台输出 logstash JSON（`logging.structured.format.console: logstash`），`TraceIdFilter`
把请求头 `X-Trace-Id`（缺省生成 32 位 hex）写入 MDC 的 `traceId` 并回写响应头；Reactor
异步链路（route-judge / Agent 流式生成所在的 boundedElastic）经 `ContextPropagationConfig`
开启自动传播后继承 MDC（模型 SDK 自建线程不在传播范围）。本地想恢复可读文本日志，启动
参数加 `--logging.structured.format.console=`。

### 数据保留期（两个清理任务，均可配、0 = 禁用该表）

| 任务 | 覆盖表 | 保留期配置（默认） | 时间基准 |
| --- | --- | --- | --- |
| `ObservabilityLogCleaner` | `llm_call_log` / `tool_call_log` / `kb_retrieval_log` / `mcp_server_log` | `app.observability.retention-days`（90） | `created_at` |
| `DataRetentionCleaner` | `session_message_archive` / `goal`（级联 `orchestration_checkpoint`）/ `delivery_outbox`（仅终态） | `app.retention.archive-days` / `goal-days`（180）、`outbox-sent-days`（7）/ `outbox-dead-days`（30） | `archived_at` / `finished_at` / `created_at` |

- 两者都分批删除（观测 1000 行/批、业务 500 行/批），失败仅 warn 不抛，下一轮调度重试；
  cron 错开（03:00 / 03:30）。
- 业务数据与日志分开配的理由：归档是 `recall_history` 的数据源，`goal` 含 objective/summary
  正文，和「丢了不心疼」的日志共用保留期会把两件事混在一起。
- `goal` 只清终态（`SUCCEEDED` / `FAILED`）且必须先级联删检查点——只删 goal 会留下既不能被
  resume、也查不到归属的 `state_json`。
- 删除会话时级联清：`session_messages`（快照）、`session_summary`（滚动摘要）、渠道绑定；
  `session_message_archive` 按上面的保留期走，**不随会话删除**（跨会话召回要用）。

### 渠道探活告警（能报什么 / 不能报什么）

`ChannelWatchdog` 每 `app.channel-watchdog.interval-ms`（默认 5 分钟）读一次各渠道的
`ChannelHealth`，连续 `app.alert.min-failures`（默认 3）次 `DOWN` 才发告警；恢复后补一条
「已恢复」；同一渠道 `app.alert.throttle-hours`（默认 24 小时）内不重复提醒。
告警本身写 `delivery_outbox`（`source=alert`），所以发失败还会按退避重投，可在表里查。

两条判定口径值得记住：

- **只对「确认不可用」告警**：`ChannelHealth.Status` 四态里，`UNKNOWN` 不是故障。邮件渠道
  用「最近一次投递」当探针，7 天没投递只能报 `UNKNOWN`——把它当故障会每周发一条假警报。
- **告警不走故障渠道**：入队前会把 `alert-chain` 里该渠道的段删掉（`qq` 挂了走
  `email:me@example.com`）。删完为空时只记 ERROR 日志——此时确实没有可用通道。

**边界（刻意不做进程外心跳）**：本层只覆盖「进程活着、渠道发不出去」。进程/主机死亡不在
范围内，理由：单实例个人部署下进程死了你通常会自己察觉；`restart: unless-stopped` 已覆盖
崩溃拉起；外部观察者本身是需要长期维护、失效时无人监控的依赖。**残余风险**：容器一直起不来
（镜像损坏、磁盘满、端口占用）时所有通知一起停，发现方式退化为「你注意到今天没收到消息」。
将来若确实需要，接一个外部探活调 `/actuator/health` 即可，不需要改本层结构。

### 飞书长连接（M3b 事件订阅）

与 M3a 的群 webhook 是两条独立链路：webhook 是「往固定群发通知」，长连接是「收消息 + 回复」。
开关也分开：`CHANNEL_FEISHU_ENABLED`（出站）/ `CHANNEL_FEISHU_WS_ENABLED`（入站）。

`.env.local` 里需要：

```bash
export CHANNEL_FEISHU_WS_ENABLED=true
export FEISHU_APP_ID=cli_xxx
export FEISHU_APP_SECRET=xxx
export CHANNEL_FEISHU_WS_ALLOWED_USERS=ou_xxx   # 多个用逗号分隔
```

自建应用侧要开三件事（缺一条就是「连上了但收不到」）：

1. 权限 `im:message`（单聊）、`im:message.group_at_msg`（群聊 @ 机器人）、`im:message:send_as_bot`（回复）
2. 事件与回调 → 事件配置 → **使用长连接接收事件**，添加 `im.message.receive_v1`
3. 创建版本并发布；可用范围要包含使用者

**配对**：`allowed-users` 为空时进入配对模式——收到消息只把 `open_id` 打进日志，不回复、不调模型。
把日志里的 `open_id` 加进白名单并重启，才能对话。这是刻意的：长连接没有回调端点，
白名单是机器人被拉进陌生群后唯一能拦住「对所有人开口」的闸。

**群聊**：群里必须 @ 机器人（依赖飞书只推送 @ 消息的语义），回复走 `receive_id_type=chat_id`；
会话按 `(open_id, chat_id)` 绑定，同一个人在单聊和不同群里各自独立上下文。

两个必须知道的坑：

- **SDK 无法停止**：oapi-sdk 2.4.19 的 `Client` 没有公开 stop，`pingLoop` 是死循环且吞中断，
  OkHttp 线程也不是守护线程——JVM 不会自然退出。`FeishuWsClient.stop()` 在断开后延迟 3 秒
  调用 `Runtime.halt(0)`；升级 SDK 后必须重跑一次「启动 → Ctrl+C」确认行为。
- **fat jar 不重打**：只改 `channel-*` 模块、`server` 源码没变时，`mvn -pl server -am package`
  会复用旧的 `server/target/*.jar`，跑起来还是旧代码。改渠道模块后一律加 `clean`。


### 渠道侧的「按会话串行」（飞书已做，QQ 待跟）

同一会话连发两条消息，第二条会基于**旧上下文**回答，历史顺序也按完成时间排。原因不在写回：
`SessionSnapshotStore.appendBatch` 用 `SELECT ... FOR UPDATE` 锁会话行，读-改-写全程串行，
**不会丢消息**。问题出在「读上下文」发生在模型调用之前、且没有锁——两条并发的消息各自读到
同一份旧上下文，后完成的把历史覆盖成「B 问、B 答、A 问、A 答」。**锁必须加在「读上下文之前」，
而不是「写回」的时候**；否则你保护了数据，没保护语义。

`FeishuChatHandler` 的做法（`handleAsync`）：

- 入站线程只做「查绑定 + 入队」就返回，不再阻塞——旧实现在入站线程上 `future.get()`，
  一个慢会话会把入站池占住。
- `ConcurrentHashMap<Long, Slot>` 按 sessionId 维护 `running` + `pending`（容量 1）。
  入口与收尾都在 `map.compute` 里改 slot：那是按 key 原子的，**`computeIfAbsent` + `synchronized`
  挡不住「收尾时移除 map、同时新线程已拿到旧 slot」的竞态**。
- 被拒收（已有 1 条排队）时回一句「上一条还在处理」，同一会话 30 秒内只提示一次，
  避免刷屏时回一长串相同的话。
- 收尾的 `finally` 必须推进队头：**漏了这一步，一次模型异常会让该会话永久卡死**——
  后续消息全部进队列然后被拒收，用户只会觉得机器人死了。

两个坑：

- **不能在聊天池内部再 `submit` 到同一个池然后 `get`**。池满时所有线程都在等自己提交的任务，
  没有线程去执行 → 死锁。所以执行放在虚拟线程上（`newVirtualThreadPerTaskExecutor`），
  模型调用仍提交到 2 线程的聊天池，超时 `cancel(true)` 的原有逻辑不变。
- **串行不等于限频**。QQ 的 `UserRateLimiter` 是防刷屏，串行是防错序，两者都得有。

测试用 `CountDownLatch` 把第一条卡在模型调用里，再断言第二条在 300ms 观察窗口内**没有**启动。
这个用例做过变异验证：把入口改成「无条件发射」，用例确实失败——否则它只是看起来在测并发。

### 飞书入站：群聊全量接收与图片多模态

群聊要「每条都回」，靠的是后台开 `im:message.group_msg`（**敏感权限**，需审核）。只有这个权限
才会把群里的普通消息推给应用；`im:message.group_at_msg` 只推 @ 机器人的。

两条容易踩的：

- **自回环**：开了 group_msg 后，事件里若带上机器人自己的消息，会自己回自己。入站按
  `sender_type` 过滤（非 `user` 一律丢）。飞书文档说不推 bot 消息，但这是权限配置正确时的前提，
  闸门不能省。
- **图片要三层才到模型**：飞书 `image_key` → 下载字节（`im:resource`）→ `ChatAttachment` →
  AgentScope `ImageBlock`。任何一层没接上，模型只收到一段文字，**不报错**。所以验证必须看
  模型能不能说出图里的内容，而不是看有没有异常日志。

已知限制：多 Agent 编排路径（`MultiAgentGraphAgent`）走 `Agent.execute` 的默认实现，附件被丢弃；
图片也不进会话快照（`saveContextBatch` 只存文本），下一轮看不到上一轮的图。

### 飞书指令：只有一个人能用

`FeishuChatHandler` 在入队之前拦 `/new` `/reset` `/help` `/history` `/agent`：不建会话、
不进会话队列、不调模型。三个设计点：

- **授权是单个 open_id，不是白名单**。白名单回答「谁能和机器人说话」，指令授权回答
  「谁能重置上下文」。群里也响应指令，而 `/new` 重置的是「这个人 + 这个群」的会话——
  放开给所有人，任何成员都能把别人正在进行的对话打散。
- **非授权用户不交给模型**。模型会顺着斜杠编一份并不存在的命令清单，比不回复更糟。
  回一句固定提示即可。
- **不认识的 `/xxx` 不拦**。用户可能只是发了条以斜杠开头的普通消息，指令层不该吞掉它。

配置 `FEISHU_WS_COMMAND_USER`（yaml: `channel.feishu.ws.command-user`）；留空回退
`allowed-users` 首位。**它必须在 `allowed-users` 里**，否则入站闸门先挡掉消息，
表现为「发指令没反应」——启动日志会打一条 warn。

### 飞书表情包：为什么和 QQ 各写一份

标记约定（`[emoji:分类]`）、数据（`emojis/` 目录 + 索引）都一样，代码却各留一份——
`channel-feishu` 看不见 `channel-qq`，抽公共模块又要为百来行代码新增一个反应堆成员，
还得同步 Dockerfile 的模块拷贝。共享的是**数据**，不是代码。

飞书侧比 QQ 多一步：图片必须先 `POST /im/v1/images` 换 `image_key`，再用
`msg_type=image` 发送——飞书不接受内联字节。上传与下载图片同属 `im:resource` 权限。

两个容易踩的点：

- **标记一律剥离**：即使 `emoji.enabled=false` 也要剥，否则关掉开关反而让正文里露出
  `[emoji:happy]`。关闭只意味着不选图、不发图。
- **模型不知道就不会发**：约定写在 `PromptAssembler` 的飞书信道规范段里。如果某次改动
  把它删了，表现是「表情功能突然不工作」，而不是报错。

### 飞书渠道的八项加固（2026-11-10）

一轮审计后补的，按「不修就静默失效」优先：

1. **绑定时清理**：`FeishuSessionBindingCleaner` 按 session_id 删绑定。缺了它，Web 删会话后
   飞书一直用已删的 id 空转——`appendBatch` 的防复活守卫会静默跳过写回，表现是「突然失忆」。
2. **长连接健康**：`FeishuWsHealth`（channelId `feishu-ws`）回答「能不能收到消息」，
   与 webhook 的「能不能发出去」分开。判据只有「运行中 + 最近有事件」，7 天无事件记 UNKNOWN。
3. **池满必回话**：聊天池拒绝时回一句「消息有点多」，否则用户看到的是永久静默。
4. **回复上限**：`reply-max-chars` 默认 3000，超长截断并标注，避免整条因体积超限发不出去。
5. **表情 key 缓存**：`image_key` 在租户内可复用，按索引名缓存，省掉每次上传。
6. **重试与令牌刷新**：401 / 429 / 5xx 重试一次；401 先清 token 缓存。
7. **处理回执**：慢轮次给原消息贴 `OnIt` 表情，`finally` 里撤销；快速回复零调用。
8. **`/status`**：新增 `contract.api.HarnessStatusPort`（server 实现复用监控页汇总），
   飞书指令输出会话 + 组件状态。端口必须只读，不得触发探活。

### 飞书回复形态（引用 / @ / 撤回 / 回复模式）

四个都在渠道层，不动 core：

- **引用回复**：改走 `POST /im/v1/messages/{id}/reply`，群里显示引用块。失败（原消息被撤回、
  删除）自动退回普通发送——宁可丢引用，不能丢回复。`reply-in-thread=true` 可切话题形式。
- **@ 提问者**：群聊文本前加 `<at user_id="ou_xxx"></at>`，与 QQ 的 `at-asker` 同名同义。
- **`/recall`**：按 `openId+chatId` 记最近一条发出的消息 id（上限 512，超出清空），
  `DELETE /im/v1/messages/{id}` 撤回。只记对话回复与表情图，不记指令回执。
- **`reply-mode`**：`all` / `mention` / `probability`。只作用于群聊的普通消息：
  单聊不限制，指令也不受限制（显式操作不该被概率吞掉）。`mention` 依赖入站事件里的
  `mentions`，所以 `FeishuInboundMessage` 新增了 `mentioned` 字段。

### 飞书语音回复：为什么必须过一道 ffmpeg（2026-11-11）

core 的 TTS 产出 mp3，飞书语音条（`msg_type=audio`）只认 opus，且上传
（`POST /im/v1/files`）必须带 `duration`（毫秒）——两个约束都不是可选的，
所以链路上必然有一次转码。QQ 不需要：它直接内联 mp3 当 `record` 段发。

不引纯 Java 方案的理由：JDK 既没有 mp3 解码器也没有 opus 编码器，为一件事引两个原生库
不如调 ffmpeg（运维也熟）。代价是**运行环境必须有 ffmpeg**：没有时 `toOpus` 返回空，
本条退回文字，日志里只 warn 一次（不刷屏）。

时长从 ffmpeg 的 stderr（`Duration: 00:00:03.48`）里取，不再单独起 ffprobe——
为了一个字段多一次进程启动不值当。`-nostdin` 不能省：服务端没有终端，ffmpeg 默认会去读
标准输入，可能直接挂住。

### 文件下发：字节为什么必须经服务端中转（2026-11-11）

沙箱容器**不挂宿主卷**（这是隔离的主防线，见 `SandboxGuards`），文件只在容器里；渠道又只
认识 HTTP。两边都不动的前提下，只剩一条路：服务端把文件读出来，放
`FileArtifactStore`，渠道按 `file:<id>` flag 取走上传。

读取走容器内的 `stat -c %s` + `base64 -w0`，**只读**，边界复用写文件的同一套
（`SandboxGuards.requireWorkspacePath`）：越界读和越界写一样危险。三个刻意的取舍：

- **单文件上限 2MB**（`app.sandbox.send-file-max-bytes`）：内存里放一份、base64 再翻
  1.33 倍，无上限就是把容器里的文件大小变成服务的堆压力。超限直接拒绝，不截断。
- **路径含单引号一律拒绝**：路径要拼进 shell 命令，单引号内无法再转义；换个文件名即可。
- **`base64` 用 MIME 解码器**：busybox 版本按 76 列折行，严格解码器会直接报错。

工具的 shell 调用抽成 `SendFileTool.Shell` 函数接口，单测注入假实现，不必起 Docker。



### cli 终端探测：为什么会卡 40 秒（2026-11-11）

`TerminalInput.open()` 原来直接调 `TerminalBuilder.terminal()`。JLine 3.30 的 `doBuild()`
在判断终端类型**之前**，会先让每个 provider 判断「系统流归不归我管」；
`ExecTerminalProvider.isPosixSystemStream` 的实现是执行外部命令 `test -t 0`，然后
`Process.waitFor()` **不带超时**。

在 Surefire fork 里那个子进程（MSYS 的 `sh`）要 39~49 秒才退出，于是 `TerminalInputTest`
单类耗时 57 秒。两个看起来该管用的开关都无效：`org.jline.terminal.dumb=true` 与
`org.jline.terminal.type=dumb` 实测仍要 39 秒——探测发生在它们生效之前（实测，非推断）。

现在的做法是**先判断再探测**：`System.console() == null` 且 `TERM` 未设或为 `dumb` 时，
本就读不到按键，直接走行式降级，不进入 JLine。两个信号的取舍：

- 有控制台（cmd / Windows Terminal / 另开的终端宿主）→ 走 JLine。JDK 22+ 即使重定向了
  标准流，只要有控制台 `System.console()` 就非 null。
- 没有控制台但 `TERM` 是 `xterm-*` 之类 → 仍走 JLine。Windows 下的 mintty（Git Bash）
  是 POSIX 伪终端，只有 TERM 能反映出来，靠 exec provider 工作，不能误判成管道。
- 两者都没有 → 管道 / CI / Surefire，直接降级。

效果：`TerminalInputTest` 57.2s → 1.2s，全量 `mvn verify` 3:18 → 2:09。

排查手法也留在这里：`jstack` 附不上 Surefire fork（`AttachNotSupportedException: 拒绝访问`），
改成在 `open()` 里挂一个 8 秒的看门狗线程打 `Thread.getAllStackTraces()`，一次就定位到
`ProcessImpl.waitForInterruptibly ← isPosixSystemStream ← TerminalBuilder.doBuild`。

### 外部工具：清单驱动，而不是给每个接口写一个类（2026-11-11）

工具面分三层，边界由「是否需要运行时上下文」决定：

| 层 | 放什么 | 为什么 |
| --- | --- | --- |
| core | `history` / `memory` / `sandbox.*` / artifact（`send_file`、TTS、点歌） | 要 `turnId`、容器句柄、工件库，外部进程拿不到 |
| external | MCP（第三方/多语言）+ 声明式 HTTP（自家 REST） | 接新系统不改 Java、不重打包 |
| skill | 编排与说明 | 把「十个近义工具」压成「一个工具 + 一份说明书」 |

声明式 HTTP 的形状是 `tools/http/*.yaml`：一个清单 = 一个工具 + N 个资源。
资源绑定**固定路径**与**参数白名单**，模型只能选资源名和参数值，不能拼路径——
这是它和通用 `http_request` 的关键区别，后者等于给所有 agent 一条无约束的出站通道。

为什么不「一个端点一个工具」：WMS 有二十个只读端点，逐个做成工具会让工具面塞满近义名字
（`wms_search_stock` / `wms_search_stock_in_orders` / `wms_get_stock_in_order`…），
模型在选工具这一步就开始出错。收敛成 `wms_query` 后，「查什么」是枚举，「怎么查」在 skill 里。

响应整形也放在清单里：`format: jeecg` 拆 `result` 包装（records 分页 / 数组 / 单对象三种形态），
`drop-fields` 剔除 `createBy` 这类审计字段。这些是数据，不该每个接入方写一遍代码。

WMS 这一步走了两版，值得记下来：

- **V18** 先把十个工具换成 `tools/http/wms.yaml`（`wms.query` pack），组合查询下沉到 skill。
- 落地后确认适配器本身更适合承载这段逻辑：它是 MCP 服务，有进程隔离、调用方鉴权，
  而且「商品名换 productId」本来就在服务端做。于是 **V19** 把分发器做进 `WmsTools`：
  只暴露一个 `wms_query(resource, params)`，十六个资源在内部映射到原有的十段查询逻辑。
- `tools/http/wms.yaml` 删除——同一批端点写两遍必然漂移。HTTP 机制保留给
  「不值得起一个服务」的轻量集成。

代价与收益：模型仍然只看到一个工具、一份说明书；不同的是组合查询回到服务端，一次调用完成，
不用模型先查商品再查库存。参数白名单从 yaml 移进 `WmsTools.ALLOWED`，
`WmsQueryDispatchTest` 与 `WmsToolListGuardTest` 一起守着（后者原来守十个工具名，现在守资源契约）。

**实测发现的一个坑**：模型会把嵌套对象序列化成字符串
（`"params":"{\"productId\":\"p1\"}"`），只接受 Map 的实现会在绑定阶段直接失败。
HTTP 工具和 MCP 分发器现在都用 `Object` 接参数、两种形态都认，
`HttpToolCallbackTest` 与 `WmsQueryDispatchTest` 各有一条用例守着。
这类问题单测造不出来——只有真跑一次、看服务端收到的 query string 才会暴露。

能力清单：`GET /api/capabilities` 按来源聚合（core/sandbox/mcp/http/skill），
每条带定位符（源文件、清单路径、URL、技能目录）与工具名；`POST /api/capabilities/reload`
重载两类外部来源：HTTP 清单与 MCP server 配置。

MCP 重载的做法：把「规格 + 连接状态」收成一份不可变快照，reload 时原子换引用；
旧连接延迟 30 秒关闭——在途调用跑完，新请求立刻走新连接。失败仍按 server 隔离，
不影响其他 server。改 `mcp-config.json` 不再需要重建容器。

`wms-mcp-adapter` 是 WMS 的唯一实现，不再删除：它是 MCP 服务，进程隔离、调用方鉴权、
组合查询都在它那边；声明式 HTTP 机制留给「不值得起一个服务」的轻量集成。

