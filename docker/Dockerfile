# ============================================================
# 多阶段构建：Maven + JDK 17 编译 → JRE 17 运行
# 构建（context 必须是项目根——COPY 依赖 pom.xml/src/skills/knowledge/channel）：
#   docker build -f docker/Dockerfile -t java-harness .
#   或直接：docker compose -f docker/docker-compose.yml up -d --build
# 说明：沿用项目 .mvn/settings.xml（阿里云镜像 + 工作区内 .mvn-repo 本地仓库），
#       依赖下载落在可缓存的独立层——pom 不变时重建秒级完成
# ============================================================

# ---------- 构建阶段 ----------
# 基础镜像参数化：默认官方名；Docker Hub 不可达的网络（国内常见）经 --build-arg
# 覆盖为加速通道地址（如 docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-17），
# compose 侧同样支持 BUILD_BASE/RUN_BASE 环境变量覆盖。
# 注意：ARG 必须都声明在首个 FROM 之前（全局作用域）——首个 FROM 之后的 ARG 归属
# 上一阶段，后续 FROM 引用会报 "base name should not be blank"
ARG BUILD_BASE=maven:3.9-eclipse-temurin-17
ARG RUN_BASE=eclipse-temurin:17-jre
FROM ${BUILD_BASE} AS build
WORKDIR /build

# 先拷贝 pom 与 Maven 配置：依赖层与源码层分离，改代码不重下依赖
COPY pom.xml ./
COPY .mvn/settings.xml .mvn/settings.xml
# dependency:go-offline 尽力预拉全部依赖与插件（个别插件依赖拉不全是已知行为，
# 失败不阻断——下一步 package 会继续补齐）
RUN mvn -s .mvn/settings.xml -B -q dependency:go-offline || true

COPY src ./src
# 测试需真实数据库环境，镜像构建跳过（本地全量回归单独跑）
RUN mvn -s .mvn/settings.xml -B -q -DskipTests package

# ---------- 运行阶段 ----------
FROM ${RUN_BASE}
WORKDIR /workspace
ENV TZ=Asia/Shanghai

COPY --from=build /build/target/javaHarness-*.jar /app/app.jar
# 运行期相对路径资源（相对启动工作目录 /workspace）：
#   skills/    —— skill 动态装配目录（app.prompt.skills.dir）
#   knowledge/ —— RAG 知识库文档目录（app.knowledge.dir）
#   channel/   —— QQ 表情映射表与表情图（napcat.emoji.*）
# 后两者可用 volume 挂载覆盖实现免重启换内容
COPY skills ./skills
COPY knowledge ./knowledge
COPY channel ./channel

# 源码含大量中文字符串，显式 UTF-8（对齐 pom 的 sourceEncoding 声明）
ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-jar", "/app/app.jar"]
