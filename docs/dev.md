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
| Node / npm | v20.18.1 / 10.8.2                    | 只给 `web/` 用           |
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

- Flyway 随 server 启动自动迁移，当前最高版本为 `V42`；改表只新增迁移文件，不改已发布的。

## 5. 前端（`web/`）

```bash
cd web
npm install
npm run dev        # 5173，proxy 到 8080
npm run typecheck  # vue-tsc --noEmit
npm run build      # typecheck + vite build
```

## 6. 密钥与环境变量

- 本地密钥放仓库根 `.env.local`（未纳入版本控制），如 `QWEN_API_KEY`、`DEEPSEEK_API_KEY`。
- `run-wsl.sh` 会 `source .env.local`；**`run-win.bat`** **不会**，Windows 侧请用 `setx` 设系统环境变量。
- 不要把密钥写进 yaml、脚本或提交记录。

## 7. 已知问题（本机实测）

- **GitHub 推送经常 408 超时**：默认推 Gitee，GitHub 失败不要反复重试。
- **`npm test`** **起不来**：Node 20.18 + jsdom 29 报 `ERR_REQUIRE_ESM`，属环境问题；用 `npm run typecheck` 代替验证，或换 Node 22+ 再试。
- **8080 被占用**：`run-win.bat` 会自动 kill 监听进程；手工排查用 `netstat -aon | findstr :8080`。
- **MySQL 反复退出**：确认用的是 `--console` 前台模式；后台方式在无服务权限的机器上容易被系统回收。
- **改了接口却编译不过**：先确认命令带了 `-am` 与 `-s .mvn/settings.xml`。

