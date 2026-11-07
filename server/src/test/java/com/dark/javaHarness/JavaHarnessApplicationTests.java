package com.dark.javaHarness;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
     * 隐式配置项必须在 yaml 里可见：这三个键此前只有 {@code @Value} 默认值，yaml 里搜不到，
     * 运维改配置时无从发现。断言的是「环境里有这个键」——若有人把 yaml 声明删掉，键仍能靠
     * {@code @Value} 默认值工作，但测试会失败，从而逼着保留声明（体检 2026-11-07 H2-4）。
     */
    @Test
    void implicitConfigKeys_areDeclaredInYaml() {
        assertEquals("600", environment.getProperty("app.context.summary-max-chars"));
        assertEquals("qwen3.8-27b", environment.getProperty("app.context.summary-model"));
        assertEquals("mcp-config.json", environment.getProperty("spring.ai.mcp.client.stdio-config-file"));
    }
}
