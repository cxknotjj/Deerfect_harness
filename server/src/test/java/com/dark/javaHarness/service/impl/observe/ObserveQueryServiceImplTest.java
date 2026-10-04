package com.dark.javaHarness.service.impl.observe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dark.javaHarness.domain.entity.KbRetrievalLogEntity;
import com.dark.javaHarness.domain.entity.LlmCallLogEntity;
import com.dark.javaHarness.domain.entity.ToolCallLogEntity;
import com.dark.javaHarness.mapper.KbRetrievalLogMapper;
import com.dark.javaHarness.mapper.LlmCallLogMapper;
import com.dark.javaHarness.mapper.ToolCallLogMapper;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ObserveQueryServiceImpl 单测（原 ToolCallControllerTest 随查询逻辑下沉迁移）：
 * /api/tool-calls 的 sessionId / serverName 可选过滤、无参全量（不带过滤条件）、
 * limit 收敛（0→1、10000→200、默认 50），统一按 id 倒序 + LIMIT 后缀；
 * 另覆盖 llm-calls / kb-retrievals 的会话过滤口径。
 */
@ExtendWith(MockitoExtension.class)
class ObserveQueryServiceImplTest {

    @Mock
    private LlmCallLogMapper llmCallLogMapper;
    @Mock
    private ToolCallLogMapper toolCallLogMapper;
    @Mock
    private KbRetrievalLogMapper kbRetrievalLogMapper;

    private ObserveQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ObserveQueryServiceImpl(llmCallLogMapper, toolCallLogMapper, kbRetrievalLogMapper);
    }

    @SuppressWarnings("unchecked")
    private <T> List<QueryWrapper<T>> capturedWrappers(BaseMapper<T> mapper, int times) {
        ArgumentCaptor<QueryWrapper<T>> captor = ArgumentCaptor.forClass((Class) QueryWrapper.class);
        verify(mapper, times(times)).selectList(captor.capture());
        return captor.getAllValues();
    }

    private <T> QueryWrapper<T> capturedWrapper(BaseMapper<T> mapper) {
        return this.capturedWrappers(mapper, 1).get(0);
    }

    /** 读取 MyBatis-Plus AbstractWrapper 的 lastSql 私有字段（last("LIMIT n") 的落点，新版为 SharedString 包装） */
    private static String lastSql(QueryWrapper<?> qw) {
        try {
            Field f = AbstractWrapper.class.getDeclaredField("lastSql");
            f.setAccessible(true);
            Object value = f.get(qw);
            if (value != null && "SharedString".equals(value.getClass().getSimpleName())) {
                Field sv = value.getClass().getDeclaredField("stringValue");
                sv.setAccessible(true);
                value = sv.get(value);
            }
            return String.valueOf(value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法读取 lastSql 字段，检查 mybatis-plus 版本", e);
        }
    }

    /* ---------------- 工具调用日志：过滤条件 ---------------- */

    @Test
    void listToolCalls_sessionIdAndServerName_addedAsEqConditions() {
        when(toolCallLogMapper.selectList(any())).thenReturn(List.of());

        service.listToolCalls("s1", "tavily", 50);

        QueryWrapper<ToolCallLogEntity> qw = capturedWrapper(toolCallLogMapper);
        String segment = qw.getSqlSegment();
        assertTrue(segment.contains("session_id"), "应含 session_id 条件，实际：" + segment);
        assertTrue(segment.contains("server_name"), "应含 server_name 条件，实际：" + segment);
        assertTrue(qw.getParamNameValuePairs().containsValue("s1"));
        assertTrue(qw.getParamNameValuePairs().containsValue("tavily"));
    }

    @Test
    void listToolCalls_noParams_noFilterConditions() {
        when(toolCallLogMapper.selectList(any())).thenReturn(List.of());

        service.listToolCalls(null, null, 50);

        QueryWrapper<ToolCallLogEntity> qw = capturedWrapper(toolCallLogMapper);
        String segment = qw.getSqlSegment();
        assertFalse(segment.contains("session_id"), "无参不应带 session_id 条件");
        assertFalse(segment.contains("server_name"), "无参不应带 server_name 条件");
        assertTrue(segment.contains("ORDER BY id DESC"), "应按 id 倒序，实际：" + segment);
        assertEquals(" LIMIT 50", lastSql(qw), "默认 limit 50");
    }

    @Test
    void listToolCalls_blankParams_treatedAsAbsent() {
        when(toolCallLogMapper.selectList(any())).thenReturn(List.of());

        service.listToolCalls("  ", "", 50);

        QueryWrapper<ToolCallLogEntity> qw = capturedWrapper(toolCallLogMapper);
        String segment = qw.getSqlSegment();
        assertFalse(segment.contains("session_id"), "空白 sessionId 视为未传");
        assertFalse(segment.contains("server_name"), "空白 serverName 视为未传");
    }

    /* ---------------- limit 收敛 ---------------- */

    @Test
    void list_limitBounds_clamped() {
        when(toolCallLogMapper.selectList(any())).thenReturn(List.of());

        service.listToolCalls(null, null, 0);
        service.listToolCalls(null, null, 10_000);

        List<QueryWrapper<ToolCallLogEntity>> wrappers = capturedWrappers(toolCallLogMapper, 2);
        assertEquals(" LIMIT 1", lastSql(wrappers.get(0)), "下限收敛为 1");
        assertEquals(" LIMIT 200", lastSql(wrappers.get(1)), "上限收敛为 200");
    }

    /* ---------------- LLM 调用 / RAG 检索日志 ---------------- */

    @Test
    void listLlmCalls_sessionIdFiltered_orderByIdDesc() {
        when(llmCallLogMapper.selectList(any())).thenReturn(List.of());

        service.listLlmCalls("s1", 50);

        QueryWrapper<LlmCallLogEntity> qw = capturedWrapper(llmCallLogMapper);
        String segment = qw.getSqlSegment();
        assertTrue(segment.contains("session_id"), "应含 session_id 条件，实际：" + segment);
        assertTrue(segment.contains("ORDER BY id DESC"), "应按 id 倒序，实际：" + segment);
    }

    @Test
    void listKbRetrievals_sessionIdFiltered_orderByIdDesc() {
        when(kbRetrievalLogMapper.selectList(any())).thenReturn(List.of());

        service.listKbRetrievals("s1", 50);

        QueryWrapper<KbRetrievalLogEntity> qw = capturedWrapper(kbRetrievalLogMapper);
        String segment = qw.getSqlSegment();
        assertTrue(segment.contains("session_id"), "应含 session_id 条件，实际：" + segment);
        assertTrue(segment.contains("ORDER BY id DESC"), "应按 id 倒序，实际：" + segment);
    }
}
