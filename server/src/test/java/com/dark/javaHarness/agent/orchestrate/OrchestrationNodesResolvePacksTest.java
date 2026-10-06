package com.dark.javaHarness.agent.orchestrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.service.AgentConfigProvider;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * resolvePacks 可分配边界单测：lead 能授出的工具 ⊆ 其自身 agent 表 tools 列声明——
 * 包内工具逐个与该列精确名比对，列外的 warn 丢弃（越权零授出）；lead 行 tools 列为空
 * （现状）→ 任何包都授不出工具，wms_* 等领域工具独属对应专家行，generic 专家不会因
 * lead 挂包而获得越权工具面；包未授出任何工具时其纪律段一并跳过。
 */
@ExtendWith(MockitoExtension.class)
class OrchestrationNodesResolvePacksTest {

    @Mock
    private AgentConfigProvider agentConfigProvider;

    private OrchestrationNodes newNode() {
        // 仅 resolvePacks 触达 agentConfigProvider，其余协作方传 null（构造器零校验）
        return new OrchestrationNodes(null, null, null, null, null, agentConfigProvider);
    }

    private AgentConfigProvider.ToolPackDef wmsPack() {
        return new AgentConfigProvider.ToolPackDef(
                List.of("wms_search_stock", "wms_search_products"), "仓储只读纪律");
    }

    /** 核心越权场景：lead 行 tools 列为空（现状）→ 任何包都授不出工具、纪律段不拼 */
    @Test
    void leadToolsEmpty_shouldGrantNothing() {
        when(agentConfigProvider.findAgentTools("lead")).thenReturn(Optional.empty());
        when(agentConfigProvider.findToolPack("wms")).thenReturn(Optional.of(wmsPack()));

        OrchestrationNodes.PackResolution r = newNode().resolvePacks("wms");
        assertTrue(r.extraToolNames().isEmpty(), "lead tools 列为空，包工具应零授出");
        assertNull(r.disciplineText(), "未授出工具的包纪律段应跳过");
    }

    /** lead tools 列包含包内工具（精确名）→ 正常授出 + 纪律段拼接 */
    @Test
    void leadToolsContainPackTools_shouldGrant() {
        when(agentConfigProvider.findAgentTools("lead"))
                .thenReturn(Optional.of("wms_search_stock , wms_search_products"));
        when(agentConfigProvider.findToolPack("wms")).thenReturn(Optional.of(wmsPack()));

        OrchestrationNodes.PackResolution r = newNode().resolvePacks("wms");
        assertEquals(List.of("wms_search_stock", "wms_search_products"), r.extraToolNames(),
                "tools 列内声明的工具应授出（token trim、保序）");
        assertTrue(r.disciplineText().contains("【领域规范·wms】"), "授出工具的包应拼纪律段");
    }

    /** lead tools 列部分覆盖 → 仅交集授出（列外工具丢弃） */
    @Test
    void leadToolsPartialMatch_shouldGrantIntersectionOnly() {
        when(agentConfigProvider.findAgentTools("lead")).thenReturn(Optional.of("wms_search_stock"));
        when(agentConfigProvider.findToolPack("wms")).thenReturn(Optional.of(wmsPack()));

        OrchestrationNodes.PackResolution r = newNode().resolvePacks("wms");
        assertEquals(List.of("wms_search_stock"), r.extraToolNames(), "仅列内工具授出");
        assertTrue(r.disciplineText().contains("【领域规范·wms】"), "有授出即拼纪律段");
    }

    /** lead tools 列与包内工具无交集 → 零授出（如 lead 列只有 web 组，包是 wms 工具） */
    @Test
    void leadToolsUnrelated_shouldGrantNothing() {
        when(agentConfigProvider.findAgentTools("lead")).thenReturn(Optional.of("web"));
        when(agentConfigProvider.findToolPack("wms")).thenReturn(Optional.of(wmsPack()));

        OrchestrationNodes.PackResolution r = newNode().resolvePacks("wms");
        assertTrue(r.extraToolNames().isEmpty(), "列外工具应全部丢弃");
        assertNull(r.disciplineText());
    }

    /** 空白 CSV → 零解析 */
    @Test
    void blankCsv_shouldBeNoop() {
        assertTrue(newNode().resolvePacks(" ").extraToolNames().isEmpty(), "空白 CSV 零解析");
        assertTrue(newNode().resolvePacks(null).extraToolNames().isEmpty(), "null CSV 零解析");
    }
}
