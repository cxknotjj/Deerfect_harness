package com.dark.javaHarness.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.runtime.sandbox.box.BaseSandbox;
import io.agentscope.runtime.sandbox.manager.SandboxService;
import io.agentscope.runtime.sandbox.manager.model.container.ContainerModel;
import io.micrometer.core.instrument.Metrics;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * SandboxToolProvider 初始化超时兜底单测：
 * - init 挂死（模拟 Docker 命名管道无超时阻塞）时，baseTools() 必须在超时上限内返回空工具面，
 *   而不是永久阻塞请求线程（线上实测：无 Docker 时管道连接挂死 3 分钟以上）
 * - 全进程只初始化一次：超时后再次取用不再等待、不再重试
 * - init 正常完成时工具面照常可用
 */
class SandboxToolProviderTest {

    /** init 挂死场景：超时后必须放行，且只尝试一次 */
    @Test
    void hangsInitDoesNotBlockCallerBeyondTimeout() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        // 用 latch 模拟「命名管道永久阻塞」：测试结束放行，避免遗留一个睡 60s 的线程
        CountDownLatch releaseInit = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        SandboxToolProvider provider = new SandboxToolProvider() {
            @Override
            protected void init() {
                attempts.incrementAndGet();
                try {
                    releaseInit.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    entered.countDown();
                }
            }
        };
        provider.initTimeoutMs = 300;

        long start = System.nanoTime();
        assertTrue(provider.baseTools().isEmpty(), "超时后应返回空工具面");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 5_000, "首次取用应在超时上限附近返回，实际 " + elapsedMs + "ms");

        assertTrue(entered.await(2, TimeUnit.SECONDS), "init 应已在后台线程执行");
        assertTrue(provider.readOnlyFileTools().isEmpty());
        assertTrue(provider.writeTools().isEmpty());
        assertEquals(1, attempts.get(), "超时后不应重试，全进程只尝试一次");
        releaseInit.countDown(); // 放行阻塞中的 init 线程
    }

    /** init 正常完成：工具面可用，且不会二次初始化 */
    @Test
    void successInitExposesToolsOnce() {
        AtomicInteger attempts = new AtomicInteger();
        SandboxToolProvider provider = new SandboxToolProvider() {
            @Override
            protected void init() {
                attempts.incrementAndGet();
                this.base = java.util.List.of(); // 无真实 Docker，仅验证放行与一次性
                this.readOnly = java.util.List.of();
                this.write = java.util.List.of();
            }
        };
        provider.initTimeoutMs = 2_000;

        provider.baseTools();
        provider.baseTools();
        provider.readOnlyFileTools();

        assertEquals(1, attempts.get(), "初始化应恰好执行一次");
    }

    /** init 抛异常：不向上传播，工具面为空 */
    @Test
    void failedInitReturnsEmptyFace() {
        SandboxToolProvider provider = new SandboxToolProvider() {
            @Override
            protected void init() {
                throw new IllegalStateException("docker not available");
            }
        };
        provider.initTimeoutMs = 2_000;

        assertTrue(provider.baseTools().isEmpty(), "初始化失败应返回空工具面而非抛出");
    }

    /** Docker 探测不可达：不触碰 SandboxService，直接降级空工具面（防「假就绪」） */
    @Test
    void dockerUnreachable_degradesToEmptyFaceWithoutSandboxService() {
        SandboxToolProvider provider = new SandboxToolProvider() {
            @Override
            protected boolean dockerReachable() {
                return false;
            }
        };
        provider.initTimeoutMs = 2_000;

        assertTrue(provider.baseTools().isEmpty(), "Docker 不可达时沙箱工具面应为空");
        assertTrue(provider.readOnlyFileTools().isEmpty());
        assertTrue(provider.writeTools().isEmpty());
        assertTrue(provider.browserTools().isEmpty(), "浏览器组同属沙箱工具，也应为空");
    }

    /** 降级必须能从指标看出来：工具面为 0 时 gauge 同步为 0，排障无需依赖日志推断 */
    @Test
    void degradedSandbox_publishesZeroToolGauge() {
        SandboxToolProvider provider = new SandboxToolProvider() {
            @Override
            protected void init() {
                throw new IllegalStateException("docker not available");
            }
        };
        provider.initTimeoutMs = 2_000;

        assertTrue(provider.baseTools().isEmpty(), "初始化失败应返回空工具面而非抛出");
        assertEquals(0.0, Metrics.globalRegistry.get("harness.sandbox.tools").gauge().value());
        assertEquals(0.0, Metrics.globalRegistry.get("harness.sandbox.browser.tools").gauge().value());
    }

    // ---------- 健康自愈（P0-1）：探活口径 / 重建触发 / 孤儿回收 ----------

    /** 探活回显包含令牌才算可用：容器活着但 shell 坏掉（回显异常）同样算故障 */
    @Test
    void probe_echoContainsToken_isHealthy() {
        BaseSandbox box = mock(BaseSandbox.class);
        when(box.runShellCommand(anyString())).thenReturn("__harness_probe_ok__\n");

        assertTrue(new SandboxToolProvider().probe(box));
    }

    @Test
    void probe_echoWithoutToken_isUnhealthy() {
        BaseSandbox box = mock(BaseSandbox.class);
        when(box.runShellCommand(anyString())).thenReturn("/bin/sh: command not found");

        assertFalse(new SandboxToolProvider().probe(box), "回显不含令牌应判故障");
    }

    @Test
    void probe_exception_isUnhealthy() {
        BaseSandbox box = mock(BaseSandbox.class);
        when(box.runShellCommand(anyString())).thenThrow(new IllegalStateException("container gone"));

        assertFalse(new SandboxToolProvider().probe(box), "容器已销毁时调用抛异常，应判故障而非上抛");
    }

    /** 容器假死：探测必须在超时内返回，不能挂住调度线程 */
    @Test
    void probe_hangs_returnsWithinTimeout() {
        BaseSandbox box = mock(BaseSandbox.class);
        // 模拟容器假死：调用一直不返回，直到 probe 超时后 cancel(true) 中断它
        CountDownLatch releaseProbe = new CountDownLatch(1);
        when(box.runShellCommand(anyString())).thenAnswer(inv -> {
            try {
                releaseProbe.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
            return "__harness_probe_ok__";
        });
        SandboxToolProvider provider = new SandboxToolProvider();
        provider.probeTimeoutMs = 100;

        long start = System.nanoTime();
        assertFalse(provider.probe(box), "超时应判故障");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 2_000, "探测应在超时上限附近返回，实际 " + elapsedMs + "ms");
        releaseProbe.countDown(); // 兜底放行（正常路径已被 cancel(true) 中断）
    }

    @Test
    void healthCheck_probeFailed_triggersRebuild() {
        AtomicInteger rebuilds = new AtomicInteger();
        SandboxToolProvider provider = new SandboxToolProvider() {
            @Override
            protected BaseSandbox currentBaseSandbox() {
                return mock(BaseSandbox.class);
            }

            @Override
            protected boolean probe(BaseSandbox box) {
                return false;
            }

            @Override
            protected void rebuildBase(BaseSandbox stale) {
                rebuilds.incrementAndGet();
            }
        };

        provider.healthCheck();

        assertEquals(1, rebuilds.get(), "探测失败应触发一次重建");
    }

    @Test
    void healthCheck_probeOk_noRebuild() {
        AtomicInteger rebuilds = new AtomicInteger();
        SandboxToolProvider provider = new SandboxToolProvider() {
            @Override
            protected BaseSandbox currentBaseSandbox() {
                return mock(BaseSandbox.class);
            }

            @Override
            protected boolean probe(BaseSandbox box) {
                return true;
            }

            @Override
            protected void rebuildBase(BaseSandbox stale) {
                rebuilds.incrementAndGet();
            }
        };

        provider.healthCheck();

        assertEquals(0, rebuilds.get(), "容器健康时不应重建");
    }

    /** 未就绪（Docker 不可达 / 初始化失败）：没有句柄可探，不应空跑探测 */
    @Test
    void healthCheck_noHandle_skipsProbe() {
        AtomicInteger probes = new AtomicInteger();
        SandboxToolProvider provider = new SandboxToolProvider() {
            @Override
            protected boolean probe(BaseSandbox box) {
                probes.incrementAndGet();
                return true;
            }
        };

        provider.healthCheck();

        assertEquals(0, probes.get());
    }

    @Test
    void healthCheck_disabled_skipsProbe() {
        AtomicInteger probes = new AtomicInteger();
        SandboxToolProvider provider = new SandboxToolProvider() {
            @Override
            protected BaseSandbox currentBaseSandbox() {
                return mock(BaseSandbox.class);
            }

            @Override
            protected boolean probe(BaseSandbox box) {
                probes.incrementAndGet();
                return true;
            }
        };
        provider.healthEnabled = false;

        provider.healthCheck();

        assertEquals(0, probes.get(), "开关关闭时不应探测");
    }

    /** 启动时发现残留容器：全部清理（单实例假设，见 cleanupOrphans 注释） */
    @Test
    void cleanupOrphans_leftoverContainers_clearsAll() {
        SandboxService svc = mock(SandboxService.class);
        when(svc.getAllSandboxes()).thenReturn(Map.of("old-1", new ContainerModel()));

        new SandboxToolProvider().cleanupOrphans(svc);

        verify(svc).cleanupAllSandboxes();
    }

    @Test
    void cleanupOrphans_noneLeftover_noop() {
        SandboxService svc = mock(SandboxService.class);
        when(svc.getAllSandboxes()).thenReturn(Map.of());

        new SandboxToolProvider().cleanupOrphans(svc);

        verify(svc, never()).cleanupAllSandboxes();
    }

    @Test
    void cleanupOrphans_disabled_noop() {
        SandboxService svc = mock(SandboxService.class);
        SandboxToolProvider provider = new SandboxToolProvider();
        provider.cleanupOrphansOnStart = false;

        provider.cleanupOrphans(svc);

        verify(svc, never()).getAllSandboxes();
    }

    /** 清理失败只记日志：残留容器占用资源，但不该阻断本次启动 */
    @Test
    void cleanupOrphans_daemonError_doesNotThrow() {
        SandboxService svc = mock(SandboxService.class);
        when(svc.getAllSandboxes()).thenThrow(new IllegalStateException("daemon gone"));

        assertDoesNotThrow(() -> new SandboxToolProvider().cleanupOrphans(svc));
    }
}
