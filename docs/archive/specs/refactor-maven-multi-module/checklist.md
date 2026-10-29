# Checklist

- [x] 三模块反应堆结构就位:根 pom `packaging=pom` 且 `<modules>shared, server, cli</modules>`,三子模块 parent 指向根 pom
- [x] shared 模块含 `domain.dto` 17 类 + `SseProtocol`,包名 `com.dark.javaHarness.*` 不变,依赖仅 jakarta.validation-api(纯 API)
- [x] server 模块含现 src 全套(减 cli 包/DTO/SseProtocol),依赖 shared,pom 无 jline(okhttp 因 QQ 渠道合法保留),channel/qq 包留守
- [x] cli 模块含 cli 包源码与 3 个测试类,依赖仅 shared + jline + okhttp + jackson-databind(+ test: junit-jupiter/mockito)
- [x] 全项目 import 零改动(HarnessController 因工厂方法收编新增 Page/SessionEntity 两个 import,属预期例外;其余文件未动 import)
- [x] 根目录 `mvn -s .mvn/settings.xml clean package` 三模块编译 + 全部测试通过(server 545 例 + cli 25 例全绿)
- [x] server fat jar 无 `com/dark/javaHarness/cli/` 目录,BOOT-INF/lib 无 jline
- [x] cli `dependency:tree` 无 spring/spring-ai 传递依赖
- [x] docker/Dockerfile 与 Dockerfile.dockerignore 已适配多模块产物路径(镜像构建实测待 Docker daemon 就绪后执行 `docker compose build`)
- [x] cli exec 启动出提示符正常(无 TTY 降级提示属预期)
- [x] `docs/HARNESS_TODO.md` P3 立条与实际落地一致(含 channel 不拆决策、okhttp 双保留、settings.xml 修复)
