# ============================================================
# 多阶段构建：Maven + JDK 25 编译 → JRE 25 运行
# 构建（context 必须是项目根——COPY 依赖 pom.xml/src/skills/knowledge/channel）：
#   docker build -f docker/Dockerfile -t deerfect-harness:local .
#   带版本信息（推荐，与 compose 的 IMAGE_TAG 对齐）：
#   docker/build-image.ps1  （Windows） / docker/build-image.sh（Linux/WSL）
# 版本策略：镜像 tag = sha-<git短sha>，APP_VERSION/GIT_SHA 同时写入 OCI labels，
#           compose 以 IMAGE_TAG 选择要运行的版本（回滚=改回旧 tag 后 up -d --no-build）。
# 说明：镜像内不挂项目的 .mvn/settings.xml（该文件不入仓库），Maven 用默认中央仓库与
#       ~/.m2；依赖下载落在可缓存的独立层——pom 不变时重建秒级完成
# ============================================================

# ---------- 构建阶段 ----------
# 基础镜像参数化：默认官方名；Docker Hub 不可达的网络（国内常见）经 --build-arg
# 覆盖为加速通道地址（如 docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-25），
# compose 侧同样支持 BUILD_BASE/RUN_BASE 环境变量覆盖。
# 注意：ARG 必须都声明在首个 FROM 之前（全局作用域）——首个 FROM 之后的 ARG 归属
# 上一阶段，后续 FROM 引用会报 "base name should not be blank"
ARG BUILD_BASE=maven:3.9-eclipse-temurin-25
ARG RUN_BASE=eclipse-temurin:25-jre
# 版本信息（构建脚本注入；直接 docker build 时取默认值）
ARG APP_VERSION=dev
ARG GIT_SHA=unknown
FROM ${BUILD_BASE} AS build
WORKDIR /build

# 先拷贝全部模块 pom：依赖层与源码层分离，改代码不重下依赖。
# 根 pom 的 <modules> 声明了 7 个模块，即使 -pl 只构建 deerfect-server，Maven 读取反应堆时
# 也要求每个模块目录存在——因此所有 pom 必须在此拷贝（deerfect-cli/wms 只建目录、不参与构建）。
COPY pom.xml ./
COPY deerfect-api/pom.xml ./deerfect-api/pom.xml
COPY channel-qq/pom.xml ./channel-qq/pom.xml
COPY channel-email/pom.xml ./channel-email/pom.xml
COPY channel-feishu/pom.xml ./channel-feishu/pom.xml
COPY deerfect-server/pom.xml ./deerfect-server/pom.xml
COPY deerfect-cli/pom.xml ./deerfect-cli/pom.xml
COPY wms-mcp-adapter/pom.xml ./wms-mcp-adapter/pom.xml
# dependency:go-offline 尽力预拉全部依赖与插件（个别插件依赖拉不全是已知行为，
# 失败不阻断——下一步 package 会继续补齐）
RUN mvn -B -q dependency:go-offline || true

COPY deerfect-api/src ./deerfect-api/src
COPY channel-qq/src ./channel-qq/src
COPY channel-email/src ./channel-email/src
COPY channel-feishu/src ./channel-feishu/src
COPY deerfect-server/src ./deerfect-server/src
# 仅构建 deerfect-server（-am 连带 deerfect-api 与 runtime 依赖 channel-qq / channel-email / channel-feishu；
# deerfect-cli/wms 不进服务端镜像）
# 测试需真实数据库环境，镜像构建跳过（本地全量回归单独跑）
RUN mvn -B -q -DskipTests -pl deerfect-server -am package

# ---------- 运行阶段 ----------
FROM ${RUN_BASE}
WORKDIR /workspace
ENV TZ=Asia/Shanghai

# 版本信息写入 OCI labels：docker inspect 可追溯版本与提交，无需进容器查
ARG APP_VERSION=dev
ARG GIT_SHA=unknown
LABEL org.opencontainers.image.title="deerfect-harness" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${GIT_SHA}"

COPY --from=build /build/deerfect-server/target/deerfect-server-*.jar /app/app.jar
# 运行期相对路径资源（相对启动工作目录 /workspace）：
#   skills/    —— skill 动态装配目录（app.prompt.skills.dir）
#   knowledge/ —— RAG 知识库文档目录（app.knowledge.dir）
#   tools/     —— 声明式 HTTP 工具清单目录（app.tools.http.dir）
#   channel/   —— QQ 表情映射表与表情图（napcat.emoji.*）
# 后几者可用 volume 挂载覆盖实现免重启换内容
COPY skills ./skills
COPY knowledge ./knowledge
COPY tools ./tools

# 源码含大量中文字符串，显式 UTF-8（对齐 pom 的 sourceEncoding 声明）
ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-jar", "/app/app.jar"]
