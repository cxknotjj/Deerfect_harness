package com.dark.javaHarness.observability;

import com.dark.javaHarness.channel.ChannelHealth;
import com.dark.javaHarness.domain.dto.ComponentStatus;
import com.dark.javaHarness.tool.builtin.SandboxToolProvider;
import com.dark.javaHarness.tool.mcp.McpToolProvider;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 组件状态汇总：外部依赖（NapCat 渠道）、Docker 沙箱、各 MCP server 是否启用且可用。
 *
 * <p>渠道状态经 harness-api 的 {@link ChannelHealth} 端口读取（core 编译期不可见 channel 实现）；
 * Docker 读取沙箱初始化结果，MCP 读取预热缓存，均不触发新的连接。
 */
@Service
public class ComponentStatusService {

    private final ObjectProvider<ChannelHealth> channelHealths;
    private final SandboxToolProvider sandbox;
    private final McpToolProvider mcp;

    public ComponentStatusService(ObjectProvider<ChannelHealth> channelHealths,
                                  SandboxToolProvider sandbox,
                                  McpToolProvider mcp) {
        this.channelHealths = channelHealths;
        this.sandbox = sandbox;
        this.mcp = mcp;
    }

    /** 组件状态列表：渠道在前，沙箱与 MCP 在后（MCP 每个 server 一条）。 */
    public List<ComponentStatus> statuses() {
        List<ComponentStatus> list = new ArrayList<>();
        channelHealths.orderedStream().forEach(health -> {
            ChannelHealth.Snapshot snapshot = health.snapshot();
            list.add(new ComponentStatus(
                    health.channelName(), snapshot.enabled(), snapshot.available(), snapshot.detail()));
        });
        if (list.isEmpty()) {
            list.add(new ComponentStatus("QQ 渠道", false, false, "channel-qq 未装配"));
        }
        list.add(docker());
        list.addAll(mcpServers());
        return list;
    }

    private ComponentStatus docker() {
        SandboxToolProvider.SandboxStatus status = sandbox.status();
        return switch (status.state()) {
            case "READY" -> new ComponentStatus("Docker 沙箱", true, true,
                    "沙箱服务已就绪（执行/文件 " + status.baseTools()
                            + " 个工具，浏览器 " + status.browserTools() + " 个）");
            case "INITIALIZING" -> new ComponentStatus("Docker 沙箱", true, false,
                    "Docker 探测/初始化中…");
            default -> new ComponentStatus("Docker 沙箱", true, false,
                    "未检测到 Docker，沙箱类工具未启用");
        };
    }

    private List<ComponentStatus> mcpServers() {
        List<McpToolProvider.McpServerStatus> servers = mcp.statuses();
        if (servers.isEmpty()) {
            return List.of(new ComponentStatus("MCP 工具", false, false,
                    "mcp-config.json 无启用的 server"));
        }
        List<ComponentStatus> list = new ArrayList<>();
        for (McpToolProvider.McpServerStatus server : servers) {
            boolean connected = "CONNECTED".equals(server.state());
            String detail = switch (server.state()) {
                case "CONNECTED" -> "已连接（" + server.transport() + "），"
                        + server.tools() + " 个工具";
                case "FAILED" -> "连接/发现失败，该 server 工具面为空";
                default -> "预热中（尚未连接）";
            };
            list.add(new ComponentStatus("MCP · " + server.name(), true, connected, detail));
        }
        return list;
    }
}
