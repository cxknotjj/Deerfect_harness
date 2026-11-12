package com.dark.javaHarness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

@SpringBootTest
class JavaHarnessApplicationTests {

    @Autowired
    private Environment environment;

    @Test
    void contextLoads() {
    }

    /**
     * 优雅停机与容器收尾配置：Spring Boot 默认 {@code immediate}，重启/滚动更新会直接断开
     * 在途请求（SSE 表现为连接重置）。这里断言配置被正确绑定到 Spring 环境——
     * 写在错误层级（如放到某个 profile 文件里而该 profile 未激活）时测试会失败。
     *
     * <p>真机验证方式（容器环境）：{@code docker compose restart app} 时观察日志出现
     * {@code Commencing graceful shutdown}，且在途 SSE 收到正常结束。
     */
    @Test
    void gracefulShutdown_isConfigured() {
        assertEquals("graceful", environment.getProperty("server.shutdown"),
                "server.shutdown 应为 graceful，否则重启会硬断在途 SSE");
        assertEquals("30s", environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase"),
                "收尾上限应为 30s，且需 ≤ docker 的 stop_grace_period");
    }

    /**
     * 隐式配置项必须在 yaml 里可见：这两个键此前只有 {@code @Value} 默认值，yaml 里搜不到，
     * 运维改配置时无从发现。断言的是「环境里有这个键」——若有人把 yaml 声明删掉，键仍能靠
     * {@code @Value} 默认值工作，但测试会失败，从而逼着保留声明（体检 2026-11-07 H2-4）。
     *
     * <p>{@code app.context.summary-model} 已随 LLM 路由改造废弃（摘要模型由 summary 系统
     * Agent 的 fast 路由决定），这里反向断言「不得回加」：任何代码再读它都会是死配置。
     */
    @Test
    void implicitConfigKeys_areDeclaredInYaml() {
        assertEquals("600", environment.getProperty("app.context.summary-max-chars"));
        assertNull(environment.getProperty("app.context.summary-model"),
                "summary-model 已废弃：摘要模型由 summary 系统 Agent 的路由决定，不得在 yaml 恢复死键");
        assertEquals("mcp-config.json", environment.getProperty("spring.ai.mcp.client.stdio-config-file"));
    }

    /**
     * 运行上限需与部署形态匹配：Hikari 默认 minimum-idle = maximum-pool-size = 10（启动即占满），
     * Tomcat 默认 200 线程 / 8192 连接——在 600m 容器 + 50 条 SSE 上限的负载下都偏大。
     * 这里断言配置真的被绑定（键名写错时 getProperty 返回 null，测试失败），
     * 防止有人为了「临时提并发」把它悄悄改回默认。
     */
    @Test
    void runtimeLimits_areConfigured() {
        assertEquals("100", environment.getProperty("server.tomcat.threads.max"));
        assertEquals("50", environment.getProperty("server.tomcat.accept-count"));
        assertEquals("2", environment.getProperty("spring.datasource.hikari.minimum-idle"));
        assertEquals("5000", environment.getProperty("spring.datasource.hikari.connection-timeout"));
    }
}
