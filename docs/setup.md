# 🚀 本地部署详解

> README「快速开始」的完整版：数据库准备、一键启动脚本、API Key 三种配置方式、agent 服务商核对。

---

MySQL 只需建库一次（表结构由 Flyway 启动时自动创建，无需手动执行脚本）。连接信息在 `src/main/resources/application.yaml` 的 `spring.datasource`（默认 `localhost:3306/harness`、账号 `root`、空密码，按需修改）：

```sql
CREATE DATABASE IF NOT EXISTS harness DEFAULT CHARACTER SET utf8mb4;
```

### ⚡ 一键启动

> [!TIP]
> 三套启动脚本任选其一（**勿同时运行**，8080 端口会冲突）：
> - **`run-wsl.bat`**（Windows 推荐）：双击自动进 WSL——编译 → 后台起服务（日志在 `/tmp/javaHarness-server.log`）→ 就绪后本窗口变 CLI
> - **`run.sh`**（WSL 终端）：`./run.sh` 全流程——编译 → 新窗口起服务 → 本终端轮询就绪 → 进入 CLI；子命令 `server / stop / cli / build / test`
> - **`run-win.bat`**（Windows 本机）：Windows 侧检出 + Windows JDK/Maven 环境时使用

### 🔧 手动启动

**1️⃣ 启动主服务**

```bash
# 打包后启动（推荐）：根聚合打包，fat jar 在 server/target/
mvn -DskipTests package
java -jar server/target/javaHarness-server-0.0.1-SNAPSHOT.jar

# 或开发模式直接运行（需先安装 shared 模块）
mvn -pl shared -DskipTests install
mvn -pl server spring-boot:run
```

**2️⃣ 另开终端，启动 CLI**

```bash
mvn -pl cli -Pcli compile exec:exec
```

> [!TIP]
> 以上命令直接使用你自己的 Maven 环境（全局 settings + 默认仓库）。国内网络可追加 `-s .mvn/settings.xml` 走阿里云镜像加速——注意它会同时把本地仓库切到项目内 `.mvn-repo/`（已 gitignore，首次会全量重新下载）。

**3️⃣ 或直接用 REST 聊天（无需 CLI）**

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"你好"}'
```

**4️⃣ （可选）配置真实 API Key**

按优先级任选其一，重启服务后即可真实对话（不配置也能启动，调用模型返回 401 占位 key 错误）：

```powershell
# 方式一：系统级环境变量（推荐，新开窗口全局生效；WSL 会话需 WSLENV 透传）
setx QWEN_API_KEY "sk-你的key"      # DashScope（通义千问）
setx DEEPSEEK_API_KEY "sk-你的key"  # DeepSeek
setx WSLENV "QWEN_API_KEY/u:DEEPSEEK_API_KEY/u"   # 透传进 WSL（Linux 侧运行时需要）

# 方式二：仓库根 .env.local（run.sh / run-wsl.bat 启动时自动加载，已被 gitignore 不入库）
#   QWEN_API_KEY=sk-你的key
#   DEEPSEEK_API_KEY=sk-你的key

# 方式三：仅当前终端会话临时生效
$env:QWEN_API_KEY = "sk-你的key"    # Windows PowerShell；WSL 用 export QWEN_API_KEY=...
```

> [!IMPORTANT]
> **首次对话前核对 agent 绑定的服务商**：种子迁移链可能把默认 agent（general/researcher）绑到
> DeepSeek 端点——只配 `QWEN_API_KEY` 时首次对话会 401。用下面 SQL 核对，若 `provider` 不是你
> 已配 key 的服务商，二选一：补配该服务商的 key，或把 agent 改绑到已配 key 的部署模型行：
>
> ```sql
> SELECT a.agent_name AS agent, p.provider, p.model, p.id AS provider_id
> FROM agent a LEFT JOIN model_provider p ON a.model_provider_id = p.id
> WHERE a.agent_name IN ('general', 'researcher');
>
> -- 改绑示例（provider_id 换成上一步查出的已配 key 的行）：
> UPDATE agent SET model_provider_id = <provider_id> WHERE agent_name = 'general';
> ```

> [!NOTE]
> 不接入 QQ 渠道？在 `application.yaml` 设 `napcat.enabled: false`（QQ 渠道为可选组件，但当前默认开启——关闭后不再有 NapCat 连接告警，其余功能不受影响）。接入与能力清单见 [`docs/qq-channel.md`](./qq-channel.md)。


---

[⬅ 返回 README](../README.md)
