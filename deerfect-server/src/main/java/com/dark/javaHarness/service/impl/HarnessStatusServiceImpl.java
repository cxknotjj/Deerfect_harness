package com.dark.javaHarness.service.impl;

import com.dark.javaHarness.contract.api.HarnessStatusPort;
import com.dark.javaHarness.contract.model.ComponentStatus;
import com.dark.javaHarness.observability.ComponentStatusService;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * {@link HarnessStatusPort} 的实现：直接复用监控页的汇总结果。
 *
 * <p>不另写一份探测逻辑——同一个问题（「渠道/沙箱/MCP 现在可用吗」）只能有一个答案来源，
 * 否则监控页显示正常、聊天里说挂了，排查时会先怀疑人生。
 */
@Service
public class HarnessStatusServiceImpl implements HarnessStatusPort {

    private final ComponentStatusService delegate;

    public HarnessStatusServiceImpl(ComponentStatusService delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<ComponentStatus> components() {
        return delegate.statuses();
    }
}
