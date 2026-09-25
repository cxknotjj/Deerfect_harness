# 🐳 Docker 部署

> README「Docker 部署」一节的完整版：镜像构建、三件套启动、`.env` 配置、ACR 免构建拉取与离线 tar 部署。

---


不想装 JDK/Maven/数据库？`docker/` 目录提供三件套一键起：**app + MySQL 8.4（主库）+ pgvector（RAG 知识库）**，数据库连接等配置全部经环境变量注入，`application.yaml` 零改动。主库建表由应用内置 Flyway 在启动时自动完成（镜像自包含 DDL，DB 容器无需挂 init 脚本）；pgvector 扩展由 `docker/pg-init/` 在首次建库时自动启用。镜像获取有三种方式：**本地构建**（1️⃣）、**阿里云 ACR 拉取**（4️⃣，免构建推荐）、**离线 tar 导入**（5️⃣，无外网环境）。

**1️⃣ 构建镜像**

```bash
# 基础镜像可直连 Docker Hub 时：
docker build -f docker/Dockerfile -t java-harness .

# Docker Hub 不可达（国内常见）时，经加速通道覆盖基础镜像：
docker build \
  --build-arg BUILD_BASE=docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-17 \
  --build-arg RUN_BASE=docker.m.daocloud.io/library/eclipse-temurin:17-jre \
  -f docker/Dockerfile -t java-harness .
```

多阶段构建：构建阶段在容器内 `mvn package`（沿用 `.mvn/settings.xml` 阿里云镜像，pom 不变时层缓存秒级重建）→ 运行阶段仅 JRE + jar + `skills/knowledge/channel` 运行期资源。

**2️⃣ 启动三件套**

```bash
# ① 启动三件套（项目根执行；首次使用先配好 docker/.env，见下方说明）
docker compose -f docker/docker-compose.yml up -d

# ② 看应用日志确认就绪：Flyway 建表 → 出现 Started JavaHarnessApplication 即成功
#    （logs 是"看日志"不是再启动一次；Ctrl+C 只是退出滚动，容器照常运行）
docker compose -f docker/docker-compose.yml logs -f app
```

**docker/.env 说明**

compose 启动时自动读取 compose 文件同目录的 `docker/.env`（`-f` 指定路径或 `cd docker` 两种方式都会读），配好后日常启动无需手动传 key。注意事项：

- **优先级**：shell 环境变量 > `docker/.env` > compose 内默认值——临时覆盖某个值时前缀即可：`QWEN_API_KEY=sk-xxx docker compose ...`
- **与 application.yaml 的关系**：jar 内 yaml 的库口令等敏感项本身就是 `${MYSQL_ROOT_PASSWORD:harness123}` 占位符写法（环境变量优先、冒号后为兜底默认值）；compose 把 `.env` 里的值同时喂给数据库容器（root 密码）和 app 容器（连接密码），两边天然一致，改口令只动 `.env` 一处（配合上条删卷注意）
- **安全**：`.env` 含 API key 与数据库口令，已在 `.gitignore` 排除，**禁止提交**；换机器部署时把它一起拷走（变量清单与逐项注释见 `docker/.env` 本体）
- **改库口令**：`MYSQL_ROOT_PASSWORD` / `PGVECTOR_PASSWORD` 修改后必须 `docker compose down -v` 删数据卷再 up，已初始化的旧卷不会自动应用新口令
- **基础镜像**：`BUILD_BASE` / `RUN_BASE` 仅 `--build` 重建镜像时生效，默认已指向 daocloud 加速通道
- **QQ 渠道**：不用 QQ 就设 `NAPCAT_ENABLED=false`；`NAPCAT_API_TOKEN` / `NAPCAT_EVENT_SECRET` 必须与 NapCat 侧配置一致，否则消息收不到/上报被拒

| 服务 | 容器名 | 说明 |
|---|---|---|
| app | `java-harness` | 8080 对外；挂载 docker.sock 沙箱可用（不需要可删该挂载） |
| mysql | `harness-mysql` | 口令 `MYSQL_ROOT_PASSWORD`（默认 `harness123`）；3306 映射仅供宿主机管理工具，不需要可删 |
| pgvector | `harness-pgvector` | 口令 `PGVECTOR_PASSWORD`（默认 `postgresql`）；宿主机 5432 被占时删映射（容器间走服务名直连） |

**compose 相对路径规则**：volumes 里的相对路径一律以 **docker-compose.yml 所在目录（`docker/`）为基准**，与在哪个目录执行命令无关（项目根 `-f docker/docker-compose.yml` 跑也一样）。因此 `../knowledge` = 项目根/knowledge（`../` 从 docker/ 上跳一级到项目根），`./config` = `docker/config`、`./pg-init` = `docker/pg-init`；容器内挂载点全部对齐 WORKDIR `/workspace`，与 jar 内相对路径配置（`app.knowledge.dir: knowledge`、`napcat.emoji.dir: channel/emojis`）正好衔接。`docker/.env` 能被自动读取也是同一条规则（compose 固定在自己的目录找 `.env`）。

常用环境变量：`DEEPSEEK_API_KEY`；QQ 渠道 `NAPCAT_API_TOKEN` / `NAPCAT_EVENT_SECRET`，不用 QQ 设 `NAPCAT_ENABLED=false`；NapCat 在远端机器时设 `NAPCAT_BASE_URL=http://<host>:3000`（默认经 host-gateway 连宿主 Docker 里的 NapCat）。

**3️⃣ 验证与知识库初始化**

```bash
curl -X POST http://localhost:8080/api/knowledge/sync   # 需真实 QWEN_API_KEY；向量数据入 pgvector
```

> [!NOTE]
> - 全新 MySQL 由 Flyway 建表 + 种子 agent；旧库存量数据（自调的 agent 行、聊天历史）迁移：`mysqldump -uroot harness --no-create-info --skip-triggers --ignore-table=harness.flyway_schema_history > seed.sql`，再 `docker exec -i harness-mysql mysql -uroot -p<口令> harness < seed.sql`
> - 知识库/技能/表情/MCP 配置已**默认外挂**（compose volumes：`../knowledge`、`../skills`、`../channel`、`../mcp-config.json`），改宿主机文件免重打镜像：`knowledge/` 改完调 sync 增量摄取（mtime 对比，免重启）；表情映射表改后 `restart app`、新增图片即时生效；`mcp-config.json` 改后 `up -d --force-recreate app`；批量调参写 `docker/config/application.yaml`（只写要改的键，改后 `restart app`，用法见该文件头注释）

**4️⃣ 从阿里云镜像仓库拉取（免构建、免传 tar）**

镜像已托管在阿里云个人版 ACR **公开仓库**（国内服务器直连快，无需登录、无需 registry-mirrors），多台部署或频繁更新时比离线 tar 省事：

```bash
REG=crpi-udfqmnkb69y8dx8r.cn-hangzhou.personal.cr.aliyuncs.com/java-harness/harness

docker pull $REG:latest
docker tag $REG:latest java-harness:latest   # 对齐 compose 的 image: java-harness（也可直接改 compose 的 image: 为仓库地址，省去 tag）
docker compose -f docker/docker-compose.yml up -d
```

> [!TIP]
> 目标机仍需 `docker/` 目录（compose + pg-init + config）与两样不进 git 的文件——`mcp-config.json` 和 `channel/emojis/` 表情图片，拷贝清单见 5️⃣。

**5️⃣ 离线部署：导出镜像到目标 Linux 服务器**

构建机与运行机不同（如构建在 Windows Docker Desktop、运行在 Linux 服务器）时，镜像包内含完整镜像层，目标机无需源码/Maven/JDK：

```bash
# 构建机：导出为单文件镜像包（Windows PowerShell / Linux 通用）
docker save java-harness -o java-harness.tar
scp java-harness.tar user@服务器IP:/opt/java-harness/
```

```bash
# 目标机：导入镜像（.tar / .tar.gz 通用，无需解压）
docker load -i java-harness.tar
# 看到 Loaded image: java-harness:latest 即成功，标签与 compose 对上，up 不触发重建
QWEN_API_KEY=sk-你的key \
docker compose -f docker/docker-compose.yml up -d
```

> [!IMPORTANT]
> - 镜像包是给 `docker load` 读取的 OCI 格式（内含 blobs/、index.json、manifest.json），**不是压缩包——别用解压软件打开它**，`load` 直接读原封的 .tar / .tar.gz；Linux 构建机可 `docker save java-harness | gzip > java-harness.tar.gz` 减小传输体积，load 同样直接读
> - **目标机必须同时拷贝 `docker/docker-compose.yml` 和 `docker/pg-init/`**——compose 把 `pg-init/` 挂载进 pgvector 容器，首次建库时预装 vector 扩展，缺了它知识库起不来；`Dockerfile`/`Dockerfile.dockerignore` 仅重建镜像时需要（整个 `docker/` 目录才几 KB，直接全拷也行）
> - mysql/pgvector 基础镜像仍在目标机现拉：国内服务器先配 `/etc/docker/daemon.json` 的 `registry-mirrors` 并重启 docker，或同样 save/load 带过去
> - compose 默认挂载的项目相对路径（`../knowledge`、`../skills`、`../channel`）目标机项目里天然就有（git 跟踪）；但两样**不进 git** 的需补传：`mcp-config.json`（不传则 up 时 Docker 自动创建同名空目录占位，MCP 读取异常——需删掉目录放真文件）与 `channel/emojis/` 表情图片（不传则表情功能静默失效）
> - 架构需一致：Docker Desktop（WSL2）产物为 `linux/amd64`，x86_64 服务器通用；ARM 服务器用不了此镜像包，需在目标机重新构建


---

[⬅ 返回 README](../../README.md)
