# Maven 多模块拆分(shared + server + cli)Spec

## Why
单模块结构下,CLI 源码与 jline/okhttp 依赖全打进服务端 fat jar(Docker 镜像带着用不到的客户端代码与依赖);未来还要加 web 端、app 端,需要契约层(DTO 单一来源)先行就位。

## What Changes
- 根 `pom.xml` 改为 parent(`packaging=pom`),聚合模块 + 统一 dependencyManagement(版本锁定:spring-ai-bom、spring-ai-alibaba-bom、mybatis-plus、mcp 0.17.0 等)+ 统一 properties(java 17、UTF-8)
- 新增 **shared** 模块:`domain.dto` 整包 17 类 + `enums.SseProtocol` 迁入;**包名保持 `com.dark.javaHarness.*` 不变,全项目 import 零改动**;零第三方依赖(纯 POJO)
- 新增 **server** 模块:现 `src/` 全套(减 cli 包、减迁出的 DTO/SseProtocol),依赖 shared;持有全部服务端依赖(减 jline/okhttp);spring-boot-maven-plugin 打 fat jar(单产物,无 classifier);channel/qq **留在 server 不拆**(决策:双向依赖 + 服务端可选组件属性)
- 新增 **cli** 模块:`com.dark.javaHarness.cli` 包(ChatCli、api/、input/、render/、ResumeStateStore)及其 3 个测试类迁入;依赖 shared + jline + okhttp(classpath ~5MB,不传递 spring-ai 全家桶);exec-maven-plugin 与 `-Pcli` profile 随迁
- docker/Dockerfile 与 Dockerfile.dockerignore 构建路径适配多模块产物(server fat jar 路径变化)
- **BREAKING**:构建命令与产物路径变化——`mvn package` 需在根目录执行、fat jar 产出至 `server/target/`;CLI 启动命令变化(exec 插件配置挪至 cli 模块)
- `docs/HARNESS_TODO.md` P3 立条已同步本方案(已完成,不在本 spec 范围)

## Impact
- Affected specs: 无既有 spec 依赖模块结构
- Affected code: `pom.xml`(改造)、`src/**`(迁移)、`docker/Dockerfile`、`docker/Dockerfile.dockerignore`、`docs/HARNESS_TODO.md`(已同步)

## ADDED Requirements

### Requirement: 多模块反应堆结构
根 pom SHALL 声明 `<modules>shared, server, cli</modules>` 且 `packaging=pom`;三个子模块 SHALL 以根 pom 为 `<parent>`(根 pom 以 spring-boot-starter-parent 为父);版本统一 `0.0.1-SNAPSHOT`。

#### Scenario: 根目录一条命令构建全模块
- **WHEN** 在项目根目录执行 `mvn -s .mvn/settings.xml clean package`
- **THEN** Maven 按依赖序(shared → server/cli)构建三个模块,全部编译且测试通过

### Requirement: shared 契约模块
shared 模块 SHALL 包含 `com.dark.javaHarness.domain.dto` 全部 17 类与 `com.dark.javaHarness.enums.SseProtocol`,SHALL NOT 声明任何第三方运行依赖(仅编译所需,如 lombok 若被 DTO 使用则 optional);server 与 cli SHALL 依赖 shared。

#### Scenario: import 零改动
- **WHEN** 迁移完成且包名保持不变
- **THEN** server 与 cli 源码中引用 DTO/SseProtocol 的 import 语句无需任何修改即可编译通过

### Requirement: server 模块
server 模块 SHALL 承载现 `src/` 全部服务端代码与资源(application.yaml、db/migration、static),SHALL 依赖 shared 并移除 jline/okhttp 依赖,SHALL 仅产出 spring-boot fat jar;channel/qq 包 SHALL 留在 server 源码内。

#### Scenario: fat jar 不含客户端内容
- **WHEN** 解开 `server/target/*.jar`(fat jar)检查
- **THEN** BOOT-INF/classes 中不存在 `com/dark/javaHarness/cli/` 目录,BOOT-INF/lib 中不存在 jline 与 okhttp jar

### Requirement: cli 模块
cli 模块 SHALL 承载 `com.dark.javaHarness.cli` 包全部源码与测试,SHALL 仅依赖 shared、jline、okhttp(及测试依赖);exec-maven-plugin(mainClass=`com.dark.javaHarness.cli.ChatCli`)与 `-Pcli` profile(exec:exec fork 模式)SHALL 配置在 cli 模块 pom。

#### Scenario: cli 依赖树收敛
- **WHEN** 在 cli 模块执行 `mvn dependency:tree`
- **THEN** 依赖树仅含 shared、jline、okhttp(及测试依赖),不存在 spring-ai/spring-boot 传递依赖

#### Scenario: cli 启动
- **WHEN** 在 cli 模块执行 exec 启动命令
- **THEN** CLI 正常启动并渲染输入提示符(无服务端时连接失败提示属预期行为)

### Requirement: 依赖方向单向无环
模块依赖 SHALL 为有向无环:`cli → shared ← server`;server SHALL NOT 依赖 cli,shared SHALL NOT 依赖任何同反应堆模块。

#### Scenario: 循环依赖拒绝
- **WHEN** 任何人尝试在 pom 中引入模块间循环依赖
- **THEN** Maven 构建直接报错拒绝(框架保证,无需额外实现)

### Requirement: Docker 构建适配
docker/Dockerfile SHALL 适配多模块产物路径(server 模块 fat jar),Dockerfile.dockerignore 排除规则 SHALL 覆盖 cli/shared 源码不进构建 context 的策略按现状最小调整;`docker compose build` SHALL 正常产出镜像。

#### Scenario: docker compose build 正常
- **WHEN** 在 docker/ 目录执行 `docker compose build`
- **THEN** 构建成功且镜像内包含 server fat jar 于原运行路径

## REMOVED Requirements
### Requirement: 单模块构建(原根 pom 直接打 fat jar)
**Reason**: CLI 与服务端打包耦合,jline/okhttp 无谓进入服务端镜像;契约层缺位阻碍 web/app 端演进。
**Migration**: 原 `src/` 树整体迁至 `server/src/`(除上述迁出物);原根 pom 的 dependencies 下放 server pom,dependencyManagement/properties 留根;README 等文档中的构建/启动命令以本 spec 验收命令为准。
