package com.dark.javaHarness.contract.api;

import com.dark.javaHarness.contract.model.ComponentStatus;
import java.util.List;

/**
 * 只读状态端口：渠道 / 沙箱 / MCP 的当前可用性。
 *
 * <p>给 IM 渠道的 {@code /status} 这类指令用——用户问「现在都正常吗」时，不该要求他
 * 打开网页控制台。实现是只读快照，<b>不得触发新的连接或探活</b>（读缓存即可），
 * 否则一条聊天指令就会变成一次对外部系统的探测。
 *
 * <p>实现可选：渠道侧按 {@code ObjectProvider} 注入，没有实现时指令降级为一句提示。
 */
public interface HarnessStatusPort {

    /** 组件状态列表：渠道在前，沙箱与 MCP 在后；detail 不得包含密钥或 URL query */
    List<ComponentStatus> components();
}
