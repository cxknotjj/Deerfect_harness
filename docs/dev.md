# 本地开发环境（dev）

> 只记录**本机实测的现状与坑**，不重复 [README](../README.md) 的快速开始；首次部署细节见 [guides/setup.md](./guides/setup.md)。
>
> 改端口、改启动方式、换依赖服务时，同一次提交内更新本文件。
>
> 最后按代码核对：2026-10-29

## 1. 工具链（Windows 侧实测）

| 工具         | 版本                                   | 说明                    |
| ---------- | ------------------------------------ | --------------------- |
| JDK        | 25（Microsoft build，`D:\env\jdk-25`）  | `JAVA_HOME` 必须指向它     |
| Maven      | 3.9.4（`D:\apache-maven-3.9.4`）       | 仓库没有 `mvnw`，直接用 `mvn` |
| Node / npm | v22.20.0 / 10.9.3                    | 只给 `web/` 用；经 nvm 安装，见第 5 节 |
| MySQL      | 8.4.5（`D:\mysql\mysql-8.4.5-winx64`） | 详见第 4 节               |

**所有 Maven 命令必须带** **`-s .mvn/settings.xml`**：该文件把本地仓库指向 `.mvn-repo/`（仓库内），并配置阿里云镜像。漏掉它会把依赖下载到默认 `~/.m2`、走公网 central，构建变慢且与启动脚本行为不一致。

```bash
mvn -s .mvn/settings.xml -DskipTests install          # 全量构建
mvn -s .mvn/settings.xml -pl server -am test          # 单模块测试（必须 -am）
mvn -s .mvn/settings.xml -pl server spring-boot:run   # 只起服务
```

- `-am` 不能省：省略会用本地仓库里旧的 `harness-api`，改接口后容易出「明明改了却没生效」。
- 全量测试走 `run-wsl.sh test`，或 `mvn -s .mvn/settings.xml test`。

## 2. 一键启动

| 入口             | 场景         | 行为                                                    |
| -------------- | ---------- | ----------------------------------------------------- |
| `run-win.bat`  | Windows 原生 | 先 `install`，再杀 8080 旧进程，另开窗口跑 server，等就绪后另开窗口跑 CLI    |
| `./run-wsl.sh` | WSL        | 同上；另有子命令 `server` / `stop` / `cli` / `build` / `test` |

- 两套入口**同一时间只能跑一个**，否则 8080 冲突。
- 服务就绪判据（脚本用的同一个）：`GET http://localhost:8080/api/harness/agents` 能返回。
- CLI 必须先 `compile` 再 `exec:exec`，否则 `target/classes` 是旧的（曾导致乱码回归）：

```bash
mvn -s .mvn/settings.xml -pl cli -Pcli compile exec:exec
```

## 3. 端口与依赖服务

| 组件            | 地址                            | 是否必需   | 说明                                                               |
| ------------- | ----------------------------- | ------ | ---------------------------------------------------------------- |
| server        | `8080`                        | 必需     | REST + SSE；MCP 端点在同端口 `/mcp`                                     |
| Web 开发服务器     | `5173`                        | 只在调前端时 | vite，proxy 到 8080                                                |
| MySQL         | `127.0.0.1:3306`，库 `harness`  | 必需     | 配置见 `application-datasource.yaml`                                |
| PgVector      | `127.0.0.1:5432`，库 `postgres` | 可选     | 仅知识库（RAG）；未启用时 `/api/knowledge*` 返回 503                          |
| Docker daemon | `127.0.0.1:2375`              | 可选     | 沙箱工具；**必须写** **`127.0.0.1`** **不要写** **`localhost`**（IPv6 解析会超时） |
| NapCat        | `127.0.0.1:3000`              | 可选     | QQ 渠道；`napcat.enabled=false` 时整链不装配                              |

## 4. 数据库

- 跑测试/起服务前先确认 3306 在监听：

```powershell
Test-NetConnection 127.0.0.1 -Port 3306
```

- Flyway 随 server 启动自动迁移，当前最高版本为 `V43`；改表只新增迁移文件，不改已发布的。

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
npm run test:e2e   # Playwright 冒烟 3 条，mock 模式，不需要后端
```

本地不想下载 Playwright 的 Chromium 时，可复用系统浏览器（CI 不设该变量，仍用自带 Chromium）：

```powershell
$env:PLAYWRIGHT_CHANNEL='chrome'; npm run test:e2e   # 系统 Chrome；Edge 用 'msedge'
```

## 6. 密钥与环境变量

- 本地密钥放仓库根 `.env.local`（未纳入版本控制），如 `QWEN_API_KEY`、`DEEPSEEK_API_KEY`。
- `run-wsl.sh` 会 `source .env.local`；**`run-win.bat`** **不会**，Windows 侧请用 `setx` 设系统环境变量。
- 不要把密钥写进 yaml、脚本或提交记录。

## 7. 已知问题（本机实测）

- **GitHub 推送经常 408 超时**：默认推 Gitee，GitHub 失败不要反复重试。
- **8080 被占用**：`run-win.bat` 会自动 kill 监听进程；手工排查用 `netstat -aon | findstr :8080`。
- **MySQL 反复退出**：确认用的是 `--console` 前台模式；后台方式在无服务权限的机器上容易被系统回收。
- **改了接口却编译不过**：先确认命令带了 `-am` 与 `-s .mvn/settings.xml`。

## 8. CI 与镜像发布（GitHub Actions）

[.github/workflows/ci.yml](../.github/workflows/ci.yml) 三个 job，push 到 `master` 与 PR 时触发：

| job       | 内容                                                                                         |
| --------- | ------------------------------------------------------------------------------------------ |
| `backend` | temurin 25 + MySQL 8.4 service；`mvn -s .mvn/settings.xml -Dmaven.repo.local=$HOME/.m2/repository -B verify`。上下文测试启动时会跑全量 Flyway 迁移，迁移校验失败即红 |
| `web`     | Node 22；`npm ci` → `npm run build`（含 vue-tsc 类型检查）→ `npm test`（vitest）                       |
| `e2e`     | Node 22 + Chromium；`npm run test:e2e`（mock 模式，不需要后端与数据库，失败上传 `playwright-report`）               |

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

| 指标                                   | 类型      | 说明 / PromQL 示例                                                                    |
| ------------------------------------ | ------- | --------------------------------------------------------------------------------- |
| `harness_sse_active`                 | gauge   | 活跃 SSE 连接数                                                                        |
| `harness_llm_first_token_seconds`    | timer   | 首 token 延迟。P95：`histogram_quantile(0.95, sum by (le) (rate(harness_llm_first_token_seconds_bucket[5m])))` |
| `harness_llm_duration_seconds`       | timer   | 单次调用耗时；route-judge P95 取 `{agent="route-judge"}`                                   |
| `harness_llm_calls_total`            | counter | 按 `status=ok/error`；错误率 = `sum(rate(...{status="error"}[5m])) / sum(rate(...[5m]))`      |
| `harness_graph_fallbacks_total`      | counter | `stage=to_agentic`（编排失败降级单 Agent）/ `digest`（聚合失败降级汇总）                                |
| `harness_route_decisions_total`      | counter | 按 `path=graph/agentic`；Graph 降级率 = `fallbacks{stage="to_agentic"}` 速率 / `path="graph"` 速率 |
| `harness_llm_tokens_total`           | counter | 按 `kind=prompt/completion/total`、`model`；每小时用量 `increase(harness_llm_tokens_total{kind="total"}[1h])` |

成本由 token 用量 × 供应商单价换算（单价随供应商调整，不在代码里固化，避免过期价格变成伪指标）。

日志：控制台输出 logstash JSON（`logging.structured.format.console: logstash`），`TraceIdFilter`
把请求头 `X-Trace-Id`（缺省生成 32 位 hex）写入 MDC 的 `traceId` 并回写响应头；Reactor
异步链路（route-judge / Agent 流式生成所在的 boundedElastic）经 `ContextPropagationConfig`
开启自动传播后继承 MDC（模型 SDK 自建线程不在传播范围）。本地想恢复可读文本日志，启动
参数加 `--logging.structured.format.console=`。

