# ============================================================
# 三阶段构建：Maven + JDK 25 编译 → jlink 裁剪运行时 → 精简运行镜像
# 构建（context 必须是项目根——COPY 依赖 pom.xml/src/skills/knowledge）：
#   docker build -f docker/Dockerfile -t deerfect-harness:local .
#   带版本信息（推荐，与 compose 的 IMAGE_TAG 对齐）：
#   docker/build-image.ps1  （Windows） / docker/build-image.sh（Linux/WSL）
# 版本策略：镜像 tag = sha-<git短sha>，APP_VERSION/GIT_SHA 同时写入 OCI labels，
#           compose 以 IMAGE_TAG 选择要运行的版本（回滚=改回旧 tag 后 up -d --no-build）。
#
# 体积说明（2026-11 实测）：
#   - fat jar 207MB：已排除 playwright driver-bundle（四平台 Node 二进制，163MB）
#     与 tea-openapi 误带的 jacoco agent；浏览器沙箱跑在独立镜像里，JVM 内不需要它
#   - 运行时 54MB：jlink 只打包应用实际引用到的 JDK 模块 + 中英文 locale
#   - 依赖层按 pom 单独缓存：CI 的 gha 层缓存可直接命中（不要换成 cache mount，见下方说明）
#   - Maven 默认走中央仓库；国内构建可加 --build-arg MAVEN_MIRROR=<镜像地址>
# ============================================================

# ---------- 全局参数（必须声明在首个 FROM 之前）----------
# 基础镜像参数化：默认官方名；Docker Hub 不可达的网络（国内常见）经 --build-arg
# 覆盖为加速通道地址（如 docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-25），
# compose 侧同样支持 BUILD_BASE/RUN_BASE 环境变量覆盖。
# RUN_BASE 必须与 BUILD_BASE 的 libc 同族：jlink 产物里的 libjvm.so 链接 glibc，
# 不能放到 alpine（musl）上跑；换 Debian/Ubuntu slim 时也要确认 glibc 版本不低于构建镜像。
ARG BUILD_BASE=maven:3.9-eclipse-temurin-25
ARG RUN_BASE=ubuntu:24.04
# 版本信息（构建脚本注入；直接 docker build 时取默认值）
ARG APP_VERSION=dev
ARG GIT_SHA=unknown

# ---------- 构建阶段 ----------
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

COPY deerfect-api/src ./deerfect-api/src
COPY channel-qq/src ./channel-qq/src
COPY channel-email/src ./channel-email/src
COPY channel-feishu/src ./channel-feishu/src
COPY deerfect-server/src ./deerfect-server/src

# 依赖层与源码层分离：go-offline 单独一层，只要 pom 不变，CI 的 gha 层缓存
# （workflow 里的 cache-from/to: type=gha）就能直接命中，不用重新下载。
# 注意：不能用 RUN --mount=type=cache 替代——缓存卷内容不会被 gha 缓存导出，
# GitHub 的临时 runner 上等于每次全量重下，比层缓存更慢。
# MAVEN_MIRROR 非空时写一份临时 settings.xml（只镜像 central），用于国内加速。
ARG MAVEN_MIRROR=
RUN if [ -n "$MAVEN_MIRROR" ]; then \
        mkdir -p /root/.m2 && \
        printf '%s' "<settings><mirrors><mirror><id>build-mirror</id><mirrorOf>central</mirrorOf><url>${MAVEN_MIRROR}</url></mirror></mirrors></settings>" \
            > /root/.m2/settings.xml; \
    fi && \
    (mvn -B -q dependency:go-offline || true)
# 仅构建 deerfect-server（-am 连带 deerfect-api 与 channel-qq / email / feishu；
# deerfect-cli/wms 不进服务端镜像）；测试需真实数据库，镜像构建跳过
RUN mvn -B -q -DskipTests -pl deerfect-server -am package

# ---------- 运行时裁剪 ----------
# 用 jdeps 扫出应用真正引用的 JDK 模块，再 jlink 生成最小运行时：
# 完整 JRE 约 200MB+，裁剪后约 54MB（含 zh/en locale、去调试符号、zip-6 压缩）。
FROM ${BUILD_BASE} AS jre
COPY --from=build /build/deerfect-server/target/deerfect-server-*.jar /tmp/app.jar
# jdeps 看不进 BOOT-INF/lib 里的嵌套 jar，先解包再分析；
# 末尾追加的模块是 jdeps 推断不到的（反射 / 服务加载 / TLS / 中文 locale）：
#   java.logging（JUL）、java.sql（JDBC 实现）、java.naming（JNDI）、java.management（JMX）、
#   java.xml（XML 解析）、jdk.crypto.ec（TLS 椭圆曲线）、jdk.zipfs（jar/zip FS）、
#   jdk.localedata + --include-locales（中文日期/数字格式）
RUN set -eu; \
    mkdir -p /tmp/x && cd /tmp/x && jar xf /tmp/app.jar; \
    CP=$(find BOOT-INF/lib -name '*.jar' | paste -sd:); \
    MODS=$(jdeps --multi-release 25 --ignore-missing-deps --print-module-deps --class-path "$CP" BOOT-INF/classes); \
    echo "jdeps modules: $MODS"; \
    jlink --add-modules "$MODS,java.logging,java.sql,java.naming,java.management,java.xml,jdk.crypto.ec,jdk.zipfs,jdk.localedata" \
          --include-locales=zh,en \
          --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
          --output /opt/jre

# ---------- 运行阶段 ----------
FROM ${RUN_BASE}
WORKDIR /workspace
ENV TZ=Asia/Shanghai
# jlink 运行时不在标准路径，显式加到 PATH（ENTRYPOINT 用的 java 即来自这里）
ENV PATH=/opt/jre/bin:$PATH

# 不跑 apt-get：受限网络（国内/企业代理）下 ubuntu 源经常 503，构建会无谓失败。
# 需要的两样东西直接从构建阶段复制——基础镜像里本来就有：
#   ca-certificates.crt —— curl/openssl 等系统工具用；JVM 自身用 $JAVA_HOME/lib/security/cacerts
#   zoneinfo            —— TZ=Asia/Shanghai 的时区数据（2MB）
# libstdc++6 / libz 由 ubuntu:24.04 基础镜像自带，无需安装。
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY --from=build /usr/share/zoneinfo /usr/share/zoneinfo

# 版本信息写入 OCI labels：docker inspect 可追溯版本与提交，无需进容器查
ARG APP_VERSION=dev
ARG GIT_SHA=unknown
LABEL org.opencontainers.image.title="deerfect-harness" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${GIT_SHA}"

COPY --from=jre /opt/jre /opt/jre
COPY --from=build /build/deerfect-server/target/deerfect-server-*.jar /app/app.jar
# 运行期相对路径资源（相对启动工作目录 /workspace）：
#   skills/    —— skill 动态装配目录（app.prompt.skills.dir）
#   knowledge/ —— RAG 知识库文档目录（app.knowledge.dir）
#   tools/     —— 声明式 HTTP 工具清单目录（app.tools.http.dir）
# 镜像内保留一份保证裸 docker run 可用；compose 会用同名只读挂载覆盖（改宿主目录即生效）
COPY skills ./skills
COPY knowledge ./knowledge
COPY tools ./tools

# 源码含大量中文字符串，显式 UTF-8（对齐 pom 的 sourceEncoding 声明）
ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-jar", "/app/app.jar"]
