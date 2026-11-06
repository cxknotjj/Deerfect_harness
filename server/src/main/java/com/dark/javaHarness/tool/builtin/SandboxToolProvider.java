package com.dark.javaHarness.tool.builtin;

import com.dark.javaHarness.observability.HarnessMetrics;
import io.agentscope.runtime.sandbox.box.BaseSandbox;
import io.agentscope.runtime.sandbox.box.BrowserSandbox;
import io.agentscope.runtime.sandbox.manager.ManagerConfig;
import io.agentscope.runtime.sandbox.manager.SandboxService;
import io.agentscope.runtime.sandbox.manager.client.container.docker.DockerClientStarter;
import io.agentscope.runtime.sandbox.manager.model.container.ContainerModel;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 容器级沙箱工具提供者（agentscope-runtime，工具封装见 {@link SandboxShellTools} /
 * {@link SandboxFileTools} / {@link SandboxBrowserTools}）。
 *
 * <p>职责：把 agentscope 沙箱里的 ToolCallback（Python 执行 / Shell / 文件读写检索 /
 * 浏览器导航与快照）按安全类别暴露给 ToolAssignments，替代已退役的宿主机工具
 * （FileTools/SearchTools/ShellTools——模型生成的代码与命令只在容器内执行，宿主机零暴露）。
 *
 * <p>容器拓扑：同一个 {@link SandboxService} 管理两个容器（同 user/session，不同镜像类型）：
 * base 容器（Python/Shell/文件）与 browser 容器（Chromium 浏览器，独立镜像）。
 *
 * <p>初始化策略（架构决策：沙箱是硬依赖、不做宿主机降级）：
 * 懒初始化（首次取用才拉起 Docker 容器）+ 专用后台线程限时初始化（默认 15s）——
 * Docker 未运行时 agentscope 的 Docker 发现会回退到 Windows 命名管道且底层无超时、
 * 可能永久阻塞，限时等待保证请求线程绝不被拖死；超时/失败时沙箱工具面为空、
 * 进程内不再重试——即无 Docker 环境下沙箱类能力整体不可用，但应用其余功能不受影响；
 * 绝不回退到宿主机执行（文件/Shell 工具已删除，无退路可走）。
 * base 与 browser 两组初始化相互独立：浏览器镜像缺失只降级浏览器工具，不影响执行/文件类。
 */
@Component
public class SandboxToolProvider {

    private static final Logger log = LoggerFactory.getLogger(SandboxToolProvider.class);

    /** 沙箱内固定的用户/会话标识（容器命名与日志归因） */
    private static final String SANDBOX_USER = "javaHarness";
    private static final String SANDBOX_SESSION = "harness";

    /** 健康探测令牌：容器内 echo 回显包含它才认为容器可用（容器活着但 shell 坏掉也算故障） */
    private static final String PROBE_TOKEN = "__harness_probe_ok__";

    private final Object lock = new Object();
    private volatile boolean initialized;

    /** 沙箱初始化专用单线程（守护线程）：Docker 管道阻塞时也不拖垮请求线程池 */
    private final ExecutorService initExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sandbox-init");
        t.setDaemon(true);
        return t;
    });
    /** 一次性初始化任务句柄：预热线程与请求线程共用，保证 init 全进程只跑一次 */
    private volatile Future<?> initTask;

    /**
     * Docker daemon 地址（application-agent.yaml app.sandbox.docker-host）：形如 host:port 或
     * tcp://host:port；非空时注入 SDK starter（探测与 SandboxService 同源），直连 TCP——
     * 供 WSL/远程 daemon 等无 unix socket 场景使用；空 = SDK 默认发现
     * （DOCKER_HOST 环境变量 / unix socket，环境变量优先级更高）。
     */
    @Value("${app.sandbox.docker-host:}")
    private String dockerHost;

    /**
     * 初始化超时上限。Docker 未运行时 agentscope 的 Docker 发现会回退到 Windows
     * 命名管道（\\.\pipe\docker_engine），该连接底层无超时、可能永久阻塞（实测挂死
     * 3 分钟以上），必须由本层兜底超时，超时后请求按"无沙箱工具"继续。
     */
    volatile long initTimeoutMs = Duration.ofSeconds(15).toMillis();

    /** 浏览器组独立初始化锁：与 base 组互不阻塞、互不牵连 */
    private final Object browserLock = new Object();
    private volatile boolean browserInitialized;

    volatile List<ToolCallback> base = List.of();
    volatile List<ToolCallback> readOnly = List.of();
    volatile List<ToolCallback> write = List.of();
    private volatile List<ToolCallback> browser = List.of();
    private volatile SandboxService service;

    /**
     * base 容器句柄。工具 callback 在创建时 bind(sandbox)，容器重建后必须重新生成整组工具，
     * 因此句柄与工具面在同一把锁内一起替换（{@link #rebuildBase}）。
     */
    private volatile BaseSandbox baseSandbox;

    /** 自愈互斥：同一时刻只允许一次重建，避免探测超时叠加时并发建容器 */
    private final AtomicBoolean healing = new AtomicBoolean();

    /**
     * 健康探测专用单线程（守护）：容器 HTTP 调用可能不响应中断，探测必须在独立线程限时等待，
     * 绝不能占住 Spring 的调度线程或请求线程。
     */
    private final ExecutorService probeExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sandbox-probe");
        t.setDaemon(true);
        return t;
    });

    /** 探测超时上限：容器已死时 runShellCommand 会挂到 TCP 超时，这里主动放弃按故障处理 */
    volatile long probeTimeoutMs = Duration.ofSeconds(10).toMillis();

    /** 健康自愈开关（application-agent.yaml app.sandbox.health-enabled）；默认值与 yaml 一致 */
    @Value("${app.sandbox.health-enabled:true}")
    volatile boolean healthEnabled = true;

    /** 启动时清理上次强杀进程遗留的容器（无 @PreDestroy 执行时的孤儿） */
    @Value("${app.sandbox.cleanup-orphans-on-start:true}")
    volatile boolean cleanupOrphansOnStart = true;

    /** 执行类：Python 代码执行 + Shell 命令（base 容器内） */
    public List<ToolCallback> baseTools() {
        ensure();
        return base;
    }

    /** 只读文件类：读文件/批量读/列目录/目录树/搜索/文件信息（base 容器内文件系统） */
    public List<ToolCallback> readOnlyFileTools() {
        ensure();
        return readOnly;
    }

    /** 写入类：写文件/编辑替换/建目录/移动（base 容器内文件系统） */
    public List<ToolCallback> writeTools() {
        ensure();
        return write;
    }

    /**
     * 浏览器类：页面导航 / 无障碍快照（抓 JS 渲染后正文）/ 点击 / 输入 / 关闭
     * （browser 容器，独立镜像 runtime-sandbox-browser，缺失时本组为空）
     */
    public List<ToolCallback> browserTools() {
        ensureBrowser();
        return browser;
    }

    /**
     * 懒初始化 base 组：init 在专用后台线程执行。
     * 首次触发时启动 init 任务并限时等待（默认 15s）：Docker 不可用时 agentscope 会
     * 阻塞在命名管道连接上（无底层超时），限时等待保证请求线程永远不被拖死。
     * 全进程只尝试一次。
     * <p>若 init 任务已在后台跑（prewarm 预热过、管道阻塞未完成），本次请求只快速
     * 检查、不阻塞等待——避免 prewarm 超时后每个请求再各等满一次超时。
     */
    private void ensure() {
        if (initialized) {
            return;
        }
        synchronized (lock) {
            if (initialized) {
                return;
            }
            Future<?> task = initTask;
            if (task == null) {
                initTask = task = initExecutor.submit(this::initGuarded);
                awaitInit(task);
            } else if (task.isDone()) {
                // prewarm 已跑完但还没置位：首次请求取一次结果（含超时/失败分支）
                awaitInit(task);
            } else {
                // 后台仍在初始化（典型：Docker 管道阻塞中）：请求不等待，按空工具面放行；
                // 若后台最终完成，volatile 工具字段填充后后续请求仍能取到
                log.warn("[sandbox] 沙箱仍在后台初始化中，本次请求不等待，沙箱工具面暂时为空");
                initialized = true;
            }
        }
    }

    /** 限时等待 init 任务完成，超时/异常/中断均记日志并放行（工具面为空，不重试） */
    private void awaitInit(Future<?> task) {
        try {
            task.get(initTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true); // 中断底层阻塞的管道/Docker 连接，防守护线程永久占用
            log.warn("[sandbox] 沙箱初始化超过 {}s（Docker 运行中？），本进程放弃等待，"
                    + "沙箱工具面为空、不回退宿主机工具", initTimeoutMs / 1000);
        } catch (ExecutionException e) {
            log.warn("[sandbox] 沙箱初始化失败（Docker 环境？），沙箱工具面为空，不回退宿主机工具: {}",
                    e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[sandbox] 等待沙箱初始化被中断，沙箱工具面为空");
        } finally {
            initialized = true;
            publishMetrics();
        }
    }

    /** 应用就绪后后台预热沙箱：Docker 可用时首请求零等待，不可用时仅在后台失败不影响请求 */
    @EventListener(ApplicationReadyEvent.class)
    public void prewarm() {
        synchronized (lock) {
            if (initTask == null) {
                initTask = initExecutor.submit(this::initGuarded);
            }
        }
    }

    /** init 的守护包装：任何异常（含取消中断）只记日志，绝不上抛拖垮后台线程 */
    private void initGuarded() {
        try {
            init();
        } catch (Throwable t) {
            log.warn("[sandbox] 沙箱初始化异常（Docker 环境？）: {}", t.toString());
        } finally {
            publishMetrics();
        }
    }

    /** 懒初始化 browser 组：与 base 组共用 SandboxService（close 时一起释放），失败只降级本组 */
    private void ensureBrowser() {
        ensure(); // browser 容器复用同一 SandboxService，先确保服务已启动
        if (browserInitialized) {
            return;
        }
        synchronized (browserLock) {
            if (browserInitialized) {
                return;
            }
            try {
                SandboxService svc = service;
                if (svc == null) {
                    log.warn("[sandbox] 沙箱服务不可用，浏览器工具组为空");
                } else {
                    BrowserSandbox browserBox = new BrowserSandbox(svc, SANDBOX_USER, SANDBOX_SESSION);
                    this.browser = List.of(
                            SandboxBrowserTools.browserNavigate(browserBox),
                            SandboxBrowserTools.browserSnapshot(browserBox),
                            SandboxBrowserTools.browserClick(browserBox),
                            SandboxBrowserTools.browserType(browserBox),
                            // 按键：agent 表 tools 列声明的页面交互四件套之一（与 legacy MCP
                            // 白名单同名），此前漏挂导致「未识别 token」告警、声明落空。
                            // 注：上游 agentscope 无滚动工具（browser_scroll 无从注册）
                            SandboxBrowserTools.browserPressKey(browserBox),
                            SandboxBrowserTools.browserClose(browserBox));
                    log.info("[sandbox] 浏览器沙箱就绪: 浏览器类={}", browser.size());
                }
            } catch (Throwable t) {
                log.warn("[sandbox] 浏览器沙箱初始化失败（runtime-sandbox-browser 镜像？），"
                        + "浏览器工具组为空，其余沙箱工具不受影响: {}", t.toString());
            }
            browserInitialized = true;
        }
        publishMetrics();
    }

    /** 把当前工具面同步到 gauge：0 即降级，监控页与告警无需再依赖日志推断。 */
    private void publishMetrics() {
        SandboxStatus s = status();
        HarnessMetrics.sandboxTools(s.baseTools(), s.browserTools());
    }

    protected void init() throws Exception {
        // Docker 可达性前置探测：agentscope 的 SandboxService.start() 内部 connect 失败只记
        // ERROR 不中断，照常返回工具句柄——无 Docker 机器上会「假就绪」（工具面注册成功、
        // 调用必败，模型反复撞墙）。此处先做一次真实 dial（docker info，无容器/镜像副作用），
        // 不可达直接降级空沙箱工具面并给出口提示。
        if (!dockerReachable()) {
            log.warn("[sandbox] 未检测到 Docker，沙箱类工具未启用"
                    + "（安装并启动 Docker 后重启应用恢复；其余功能不受影响）");
            return;
        }
        SandboxService svc;
        DockerClientStarter starter = parseDockerStarter(dockerHost);
        if (starter != null) {
            // 配置了 docker-host：starter 注入 SandboxService（其内部 DockerClient 同源）
            svc = new SandboxService(ManagerConfig.builder().clientStarter(starter).build());
            log.info("[sandbox] 使用配置的 Docker daemon: {}:{}", starter.getHost(), starter.getPort());
        } else {
            svc = new SandboxService(ManagerConfig.builder().build());
        }
        svc.start();
        cleanupOrphans(svc);
        BaseSandbox box = new BaseSandbox(svc, SANDBOX_USER, SANDBOX_SESSION);
        registerBaseTools(box);
        this.baseSandbox = box;
        this.service = svc;
        log.info("[sandbox] 容器级沙箱就绪: 执行类={} 只读={} 写入={}",
                base.size(), readOnly.size(), write.size());
    }

    /**
     * 按容器句柄生成 base 组工具面。工具 callback 在创建时 bind(sandbox)，因此重建容器后
     * 必须整组重生成——旧 callback 仍指向已销毁的容器，复用会一直报错。
     */
    private void registerBaseTools(BaseSandbox box) {
        List<ToolCallback> executables = List.of(
                SandboxShellTools.runPythonCode(box),
                SandboxShellTools.runShellCommand(box));
        List<ToolCallback> readOnlyTools = List.of(
                SandboxFileTools.readFile(box),
                SandboxFileTools.readMultipleFiles(box),
                SandboxFileTools.listDirectory(box),
                SandboxFileTools.directoryTree(box),
                SandboxFileTools.searchFiles(box),
                SandboxFileTools.getFileInfo(box));
        List<ToolCallback> writeTools = List.of(
                SandboxFileTools.writeFile(box),
                SandboxFileTools.editFile(box),
                SandboxFileTools.createDirectory(box),
                SandboxFileTools.moveFile(box));
        this.base = executables;
        this.readOnly = readOnlyTools;
        this.write = writeTools;
    }

    /**
     * 启动时回收上次进程遗留的容器。
     *
     * <p>为什么需要：正常退出由 {@link #shutdown()} 的 {@code svc.close()} 回收，但强杀进程
     * （IDE 停止、断电、容器重启）不会执行 @PreDestroy，容器会一直挂在 daemon 上，下次启动
     * 又会新建一套——磁盘与端口都泄漏。
     *
     * <p>为什么用 cleanupAllSandboxes：此时本进程还没建过任何容器，daemon 上的沙箱容器都是
     * 上一轮遗留。**前提是单实例部署**（本项目 docker-compose 的形态）；若同一 daemon 上跑
     * 多个实例，后来启动的会清掉先启动者的容器——那时应改为按容器名前缀过滤。
     */
    protected void cleanupOrphans(SandboxService svc) {
        if (!cleanupOrphansOnStart) {
            return;
        }
        try {
            Map<String, ContainerModel> existing = svc.getAllSandboxes();
            if (existing == null || existing.isEmpty()) {
                return;
            }
            log.warn("[sandbox] 启动时发现 {} 个残留容器（上次未正常退出），开始清理", existing.size());
            svc.cleanupAllSandboxes();
            log.info("[sandbox] 残留容器已清理");
        } catch (Throwable t) {
            // 清理失败不阻断初始化：容器能建就能用，残留只是占用资源
            log.warn("[sandbox] 残留容器清理失败（不影响本次初始化）: {}", t.toString());
        }
    }

    /**
     * 周期健康探测与自愈：容器可能因 OOM、docker restart、镜像更新而失效，而沙箱句柄还
     * 持有旧容器 ID——之后每次工具调用都失败，模型会反复撞墙重试，用户看到的是「工具一直
     * 报错」而不是「沙箱挂了」。探测放在独立线程限时执行：容器假死时 runShellCommand 会挂到
     * TCP 超时，不能占住调度线程。
     */
    @Scheduled(fixedDelayString = "${app.sandbox.health-interval-ms:300000}")
    public void healthCheck() {
        if (!healthEnabled) {
            return;
        }
        BaseSandbox current = currentBaseSandbox();
        if (current == null) {
            return; // 未就绪（Docker 不可达 / 初始化失败 / 重建失败中），没有可自愈的句柄
        }
        if (!healing.compareAndSet(false, true)) {
            return; // 上一次自愈还没结束
        }
        try {
            if (probe(current)) {
                return;
            }
            log.warn("[sandbox] base 容器健康探测失败，销毁并重建");
            rebuildBase(current);
        } finally {
            healing.set(false);
        }
    }

    /** 当前 base 容器句柄；单测在无 Docker 环境下覆盖本方法注入桩（探测逻辑与容器无关） */
    protected BaseSandbox currentBaseSandbox() {
        return baseSandbox;
    }

    /** 容器探活：容器内 echo 固定令牌，回显包含令牌才算可用（异常 / 超时 / 空回显都算故障） */
    protected boolean probe(BaseSandbox box) {
        Future<String> future = probeExecutor.submit(() -> box.runShellCommand("echo " + PROBE_TOKEN));
        try {
            String out = future.get(probeTimeoutMs, TimeUnit.MILLISECONDS);
            return out != null && out.contains(PROBE_TOKEN);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("[sandbox] 容器探测超时 {}ms（容器假死？）", probeTimeoutMs);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            log.warn("[sandbox] 容器探测异常: {}", e.getCause() == null ? e : e.getCause().toString());
            return false;
        }
    }

    /**
     * 重建 base 容器：销毁旧容器 → 新建 → 重新生成工具面。
     *
     * <p>失败时清空工具面而不是留着指向死容器的旧 callback：空工具面会让模型直接说「没有
     * 执行能力」，指向死容器则会让它反复重试同一个必败调用。
     */
    protected void rebuildBase(BaseSandbox stale) {
        try {
            stale.close(); // 等价于 SandboxService.stopAndRemoveSandbox(sandboxId)
        } catch (Throwable t) {
            log.warn("[sandbox] 旧容器销毁失败（继续重建）: {}", t.toString());
        }
        SandboxService svc = service;
        if (svc == null) {
            clearBaseTools();
            publishMetrics();
            return;
        }
        try {
            BaseSandbox fresh = new BaseSandbox(svc, SANDBOX_USER, SANDBOX_SESSION);
            registerBaseTools(fresh);
            this.baseSandbox = fresh;
            log.info("[sandbox] base 容器重建完成: 执行类={} 只读={} 写入={}",
                    base.size(), readOnly.size(), write.size());
        } catch (Throwable t) {
            clearBaseTools();
            log.warn("[sandbox] base 容器重建失败，执行/文件类工具已降级为空: {}", t.toString());
        } finally {
            publishMetrics();
        }
    }

    private void clearBaseTools() {
        this.base = List.of();
        this.readOnly = List.of();
        this.write = List.of();
        this.baseSandbox = null;
    }

    /**
     * Docker 可达性探测：执行一次真实 dial（docker info）。
     *
     * <p>复用 agentscope 同源的 docker-java 客户端（{@code DockerClient} 无参构造走
     * {@code DefaultDockerClientConfig.createDefaultConfigBuilder()} 的标准发现——
     * DOCKER_HOST 环境变量 / Linux 默认 unix socket / Windows 默认命名管道），与
     * SandboxService 内部连接语义一致；无容器创建、无镜像操作，仅 daemon 级探活。
     * agentscope 的 {@code connect()} 失败只记日志返回 false、不抛异常。
     */
    protected boolean dockerReachable() {
        try {
            DockerClientStarter starter = parseDockerStarter(dockerHost);
            io.agentscope.runtime.sandbox.manager.client.container.docker.DockerClient probe =
                    starter == null
                            ? new io.agentscope.runtime.sandbox.manager.client.container.docker.DockerClient()
                            : new io.agentscope.runtime.sandbox.manager.client.container.docker.DockerClient(starter);
            boolean ok = probe.connect();
            if (!ok) {
                log.info("[sandbox] Docker 探测不可达（connect=false）");
            }
            return ok;
        } catch (Throwable t) {
            log.info("[sandbox] Docker 探测异常: {}", t.toString());
            return false;
        }
    }

    /**
     * 解析 docker-host 配置（形如 host:port 或 tcp://host:port）为 SDK starter：
     * 空白/解析失败返回 null（回退 SDK 默认发现），合法值构建 TCP 直连 starter。
     * 端口缺省按 Docker 惯例 2375。
     */
    private static DockerClientStarter parseDockerStarter(String spec) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        String s = spec.trim();
        if (s.regionMatches(true, 0, "tcp://", 0, 6)) {
            s = s.substring(6);
        }
        int idx = s.lastIndexOf(':');
        String host = idx > 0 ? s.substring(0, idx) : s;
        int port = 2375;
        if (idx > 0) {
            try {
                port = Integer.parseInt(s.substring(idx + 1));
            } catch (NumberFormatException e) {
                log.warn("[sandbox] docker-host 端口非法（{}），回退 SDK 默认发现", spec);
                return null;
            }
        }
        if (host.isBlank()) {
            log.warn("[sandbox] docker-host 缺少主机（{}），回退 SDK 默认发现", spec);
            return null;
        }
        return DockerClientStarter.builder().host(host).port(port).build();
    }

    /** 沙箱运行状态快照：供监控页展示 Docker 是否可用与已注册的工具面。 */
    public SandboxStatus status() {
        SandboxService svc = service;
        boolean ready = svc != null;
        Future<?> task = initTask;
        boolean done = task != null && task.isDone();
        String state = ready ? "READY" : done ? "UNAVAILABLE" : "INITIALIZING";
        return new SandboxStatus(
                state,
                base.size() + readOnly.size() + write.size(),
                browser.size());
    }

    /** READY=沙箱服务已就绪；INITIALIZING=Docker 探测中；UNAVAILABLE=Docker 不可达或初始化失败。 */
    public record SandboxStatus(String state, int baseTools, int browserTools) {
    }

    /** 应用退出时释放沙箱服务与全部容器（base + browser），并关闭初始化/探测线程池 */
    @PreDestroy
    public void shutdown() {
        initExecutor.shutdownNow();
        probeExecutor.shutdownNow();
        try {
            SandboxService svc = service;
            if (svc != null) {
                svc.close();
                log.info("[sandbox] 沙箱服务已关闭");
            }
        } catch (Exception e) {
            log.warn("[sandbox] 沙箱关闭异常: {}", e.getMessage());
        }
    }
}
