# Tasks

- [x] Task 1: 目录迁移(包名全部不变,仅动物理位置)
  - [x] 1.1 建 `server/`、`shared/`、`cli/` 目录骨架;整体 `mv src server/src`(普通 mv,不碰 git 索引)
  - [x] 1.2 `mv server/src/main/java/com/dark/javaHarness/domain/dto shared/src/main/java/com/dark/javaHarness/domain/dto`(整包 17 类)
  - [x] 1.3 `mv server/src/main/java/com/dark/javaHarness/enums/SseProtocol.java shared/src/main/java/com/dark/javaHarness/enums/`(AgentConstants/GoalStatus 留守 server)
  - [x] 1.4 `mv server/src/main/java/com/dark/javaHarness/cli cli/src/main/java/com/dark/javaHarness/cli`;`mv server/src/test/java/com/dark/javaHarness/cli cli/src/test/java/com/dark/javaHarness/cli`(3 个测试类)
- [x] Task 1b(实施中发现的补充项): DTO 瘦身——`SessionPageView.from(Page<SessionEntity>)`/`Item.from`/`GoalView.from(Goal)` 三个工厂方法引用 server 侧类型,挪至 HarnessController 私有静态方法,shared 侧 DTO 纯 record 化(3 处调用点同步改)
- [x] Task 2: pom 四件套改造
  - [x] 2.1 根 pom 改造:`packaging=pom` + `<modules>` + properties/java.version/编码保留 + dependencyManagement 全部上收(新增 jline/okhttp/jsoup 版本锁定)+ lombok 放根 dependencies(optional 继承)+ maven-compiler-plugin(lombok)留根 build
  - [x] 2.2 新建 `shared/pom.xml`:仅 jakarta.validation-api(@NotBlank 编译期 API,版本由 spring-boot BOM 管理)
  - [x] 2.3 新建 `server/pom.xml`:parent 指根;原根 pom 的全部 dependencies 迁入(移除 jline;okhttp 因 QQ 渠道 NapCat HTTP 调用双保留),加 shared 依赖;spring-boot-maven-plugin 保留(单 fat jar)
  - [x] 2.4 新建 `cli/pom.xml`:parent 指根;依赖 shared + jline + okhttp + jackson-databind(ChatCli/ChatApiClient 的 ObjectMapper,实施时发现原靠 server 传递)+ junit-jupiter/mockito-core(test);exec-maven-plugin(mainClass=ChatCli)+ `-Pcli` profile 随迁
  - [x] 2.5 验证编译:根目录 `mvn -s .mvn/settings.xml clean package` 全模块编译 + 测试通过
- [x] Task 2b(实施中发现的补充项): `.mvn/settings.xml` 的 localRepository 由 `${user.dir}` 修为 `${maven.multiModuleProjectDirectory}`——多模块后需在子目录执行 mvn,user.dir 漂移会让本地仓库裂成多份、shared 解析失败
- [x] Task 3: Docker 构建适配
  - [x] 3.1 Dockerfile:COPY 根 pom + 三子 pom;源码只拷 shared/src + server/src(cli 不进镜像);package 改 `-pl server -am`;产物路径 `server/target/javaHarness-server-*.jar`;Dockerfile.dockerignore 加 `**/target`
- [x] Task 3b(实施中发现的补充项): 启动脚本/文档适配多模块——run.sh(server 启动前先 install shared;compile 步改 install;cli 启动加 `-pl cli`)、run-win.bat、README 手动启动命令;`-Pcli` 等 profile 型命令行目标必须 `-pl cli` 限定,否则会在反应堆全部模块执行而报错
- [x] Task 4: 全量回归验证
  - [x] 4.1 根目录 `mvn -s .mvn/settings.xml clean package` 三模块编译 + 全部测试通过(server 545 例 + cli 25 例,BUILD SUCCESS)
  - [x] 4.2 server fat jar 解包验证:无 `com/dark/javaHarness/cli/`、BOOT-INF/lib 无 jline(okhttp 合法保留)
  - [x] 4.3 cli 模块 `mvn dependency:tree` 验证:仅 shared + jline + okhttp + jackson(+ test 依赖),无 spring/spring-ai 传递
- [x] Task 5: CLI 启动验证
  - [x] 5.1 cli 模块 exec 启动,横幅/降级提示/`你>` 提示符正常,无服务端时连接失败提示符合预期

# Task Dependencies
- Task 2 depends on Task 1
- Task 3 depends on Task 1(可与 Task 2 并行,最终验证合流)
- Task 4 depends on Task 2、Task 3
- Task 5 depends on Task 2
