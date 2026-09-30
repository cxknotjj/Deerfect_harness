package com.dark.javaHarness.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dark.javaHarness.domain.AgentConfig;
import com.dark.javaHarness.domain.entity.AgentEntity;
import com.dark.javaHarness.domain.entity.ModelProviderEntity;
import com.dark.javaHarness.mapper.AgentMapper;
import com.dark.javaHarness.mapper.ModelProviderMapper;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * AgentConfigProvider 单测：按 agentId 查名称、按 agentName 读部署模型绑定/提示词。
 * agent.model_provider_id → model_provider.id 解析模型名。
 */
@ExtendWith(MockitoExtension.class)
class AgentConfigProviderTest {

    @Mock
    private AgentMapper agentMapper;
    @Mock
    private ModelProviderMapper modelProviderMapper;

    private AgentConfigProvider provider;

    @BeforeAll
    static void initEntityMeta() {
        // LambdaQueryWrapper 的 select(lambda 列) 依赖 TableInfo 缓存（单测无 MyBatis 环境，与
        // GoalServiceImplTest 同法手工初始化），否则 findToolPack/findAgentTools 构建 wrapper 即抛异常
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), AgentEntity.class);
    }

    private AgentConfigProvider newProvider() {
        return new AgentConfigProvider(agentMapper, modelProviderMapper);
    }

    @Test
    void findAgentNameById_hit_shouldReturnWriter() {
        provider = newProvider();
        AgentEntity row = new AgentEntity();
        row.setAgentId(2L);
        row.setAgentName("writer");
        when(agentMapper.selectById(2L)).thenReturn(row);

        assertEquals("writer", provider.findAgentNameById(2L).orElse("general"));
    }

    @Test
    void findAgentNameById_miss_shouldReturnEmpty() {
        provider = newProvider();
        when(agentMapper.selectById(999L)).thenReturn(null);

        assertTrue(provider.findAgentNameById(999L).isEmpty(), "agentId=999 无记录应回退空");
    }

    @Test
    void findAgentNameById_null_shouldReturnEmpty() {
        provider = newProvider();
        assertTrue(provider.findAgentNameById(null).isEmpty(), "null agentId 应返回空");
    }

    @Test
    void getAgentConfig_hit_shouldResolveModelNameFromProviderRow() {
        provider = newProvider();
        AgentEntity row = new AgentEntity();
        row.setAgentName("writer");
        row.setModelProviderId(7L);
        row.setPrompt("你是写作助手");
        // getAgentConfig 使用 LambdaQueryWrapper.selectOne，这里 mock 返回该行
        when(agentMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(row);
        ModelProviderEntity mp = new ModelProviderEntity();
        mp.setId(7L);
        mp.setModel("gpt-4o");
        when(modelProviderMapper.selectById(7L)).thenReturn(mp);

        Optional<AgentConfig> cfg = provider.getAgentConfig("writer");
        assertTrue(cfg.isPresent());
        assertEquals(7L, cfg.get().modelProviderId());
        assertEquals("gpt-4o", cfg.get().model(), "模型名应由 model_provider.id 解析");
        assertEquals("你是写作助手", cfg.get().prompt());
    }

    @Test
    void getAgentConfig_miss_shouldReturnEmpty() {
        provider = newProvider();
        when(agentMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(null);

        assertTrue(provider.getAgentConfig("nonexistent").isEmpty());
    }

    @Test
    void getAgentConfig_nullModelProviderId_shouldReturnNullModel() {
        provider = newProvider();
        AgentEntity row = new AgentEntity();
        row.setAgentName("general");
        row.setModelProviderId(null);
        row.setPrompt(null);
        when(agentMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(row);

        Optional<AgentConfig> cfg = provider.getAgentConfig("general");
        assertTrue(cfg.isPresent());
        assertEquals(null, cfg.get().modelProviderId());
        assertEquals(null, cfg.get().model(), "未绑定部署模型应返回 null（走默认客户端）");
    }

    @Test
    void getAgentConfig_modelProviderRowMissing_shouldReturnNullModel() {
        provider = newProvider();
        AgentEntity row = new AgentEntity();
        row.setAgentName("general");
        row.setModelProviderId(404L);
        when(agentMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(row);
        when(modelProviderMapper.selectById(404L)).thenReturn(null);

        Optional<AgentConfig> cfg = provider.getAgentConfig("general");
        assertTrue(cfg.isPresent());
        assertEquals(null, cfg.get().model(), "部署模型行不存在应回退 null（走默认客户端）");
    }

    @Test
    void listAgentNames_multiRows_shouldFilterBlankNames() {
        provider = newProvider();
        AgentEntity general = new AgentEntity();
        general.setAgentName("general");
        AgentEntity writer = new AgentEntity();
        writer.setAgentName("writer");
        AgentEntity blank = new AgentEntity();
        blank.setAgentName("  ");
        when(agentMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(general, writer, blank));

        List<String> names = provider.listAgentNames();

        assertEquals(List.of("general", "writer"), names, "应返回行名并过滤空白名");
    }

    @Test
    void listAgentNames_emptyTable_shouldReturnEmptyList() {
        provider = newProvider();
        when(agentMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        assertTrue(provider.listAgentNames().isEmpty(), "空表应返回空列表");
    }

    @Test
    void listAgentNames_queryError_shouldReturnEmptyListNotThrow() {
        provider = newProvider();
        when(agentMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("db down"));

        assertTrue(provider.listAgentNames().isEmpty(), "查询异常应返回空列表而非抛出");
    }

    // ================================================================
    // 工具包定义（findToolPack）：包名 = is_internal=0 且 tools 列非空的行名
    // ================================================================

    @Test
    void findToolPack_hit_shouldParseToolNamesAndPrompt() {
        provider = newProvider();
        AgentEntity row = new AgentEntity();
        row.setIsInternal(0);
        row.setTools("wms_stock_query, wms_inbound_create");
        row.setPrompt("仓储操作纪律：先查后写");
        when(agentMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(row);

        Optional<AgentConfigProvider.ToolPackDef> pack = provider.findToolPack("wms");

        assertTrue(pack.isPresent());
        assertEquals(List.of("wms_stock_query", "wms_inbound_create"), pack.get().toolNames(),
                "tools CSV 按逗号拆分、trim、保序");
        assertEquals("仓储操作纪律：先查后写", pack.get().disciplinePrompt());
    }

    @Test
    void findToolPack_internalRow_shouldReturnEmpty() {
        provider = newProvider();
        AgentEntity row = new AgentEntity();
        row.setIsInternal(1);
        row.setTools("wms_stock_query");
        row.setPrompt("内部角色");
        when(agentMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(row);

        assertTrue(provider.findToolPack("lead").isEmpty(), "is_internal=1 的编排内部行不是工具包");
    }

    @Test
    void findToolPack_nullInternal_shouldReturnEmpty() {
        provider = newProvider();
        AgentEntity row = new AgentEntity();
        row.setIsInternal(null);
        row.setTools("wms_stock_query");
        when(agentMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(row);

        assertTrue(provider.findToolPack("wms").isEmpty(), "is_internal NULL 防御性视为非包");
    }

    @Test
    void findToolPack_blankTools_shouldReturnEmpty() {
        provider = newProvider();
        AgentEntity row = new AgentEntity();
        row.setIsInternal(0);
        row.setTools("   ");
        when(agentMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(row);

        assertTrue(provider.findToolPack("wms").isEmpty(), "tools 空白 → 非包");
    }

    @Test
    void findToolPack_onlyBlankTokens_shouldReturnEmpty() {
        provider = newProvider();
        AgentEntity row = new AgentEntity();
        row.setIsInternal(0);
        row.setTools(" , , ");
        when(agentMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(row);

        assertTrue(provider.findToolPack("wms").isEmpty(), "tools 拆分后无有效项 → 非包");
    }

    @Test
    void findToolPack_rowMissing_shouldReturnEmpty() {
        provider = newProvider();
        when(agentMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(null);

        assertTrue(provider.findToolPack("no-such-pack").isEmpty(), "无该行 → 非包");
    }

    @Test
    void findToolPack_queryError_shouldReturnEmptyNotThrow() {
        provider = newProvider();
        when(agentMapper.selectOne(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("db down"));

        assertTrue(provider.findToolPack("wms").isEmpty(), "查询异常应返回 empty 而非抛出");
    }

    @Test
    void findToolPack_nullOrBlankName_shouldReturnEmpty() {
        provider = newProvider();

        assertTrue(provider.findToolPack(null).isEmpty(), "null 包名应返回 empty");
        assertTrue(provider.findToolPack("  ").isEmpty(), "空白包名应返回 empty");
    }

    private AgentEntity agentRow(Integer thinking) {
        AgentEntity row = new AgentEntity();
        row.setAgentName("general");
        row.setThinking(thinking);
        return row;
    }

    /** thinking 列三态读取（显示口径）：1=透传 true；0/NULL=不透传 false */
    @Test
    void getAgentConfig_thinkingThreeStates() {
        provider = newProvider();

        when(agentMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(agentRow(1));
        assertTrue(provider.getAgentConfig("general").orElseThrow().thinking(),
                "thinking=1 应映射为 true（透传显示）");

        when(agentMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(agentRow(0));
        assertTrue(!provider.getAgentConfig("general").orElseThrow().thinking(),
                "thinking=0 应映射为 false（不透传）");

        when(agentMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(agentRow(null));
        assertTrue(!provider.getAgentConfig("general").orElseThrow().thinking(),
                "thinking=NULL 应映射为 false（不透传）");
    }
}