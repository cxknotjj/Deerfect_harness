package com.dark.javaHarness.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
 * ToolCallController 单测：/api/tool-calls 的 sessionId / serverName 可选过滤、
 * 无参全量（不带过滤条件）、limit 收敛（0→1、10000→200、默认 50），
 * 统一按 id 倒序 + LIMIT 后缀。
 */
@ExtendWith(MockitoExtension.class)
class ToolCallControllerTest {

    @Mock
    private ToolCallLogMapper mapper;

    private ToolCallController controller;

    @BeforeEach
    void setUp() {
        controller = new ToolCallController(mapper);
    }

    @SuppressWarnings("unchecked")
    private List<QueryWrapper<com.dark.javaHarness.domain.entity.ToolCallLogEntity>> capturedWrappers(int times) {
        ArgumentCaptor<QueryWrapper<com.dark.javaHarness.domain.entity.ToolCallLogEntity>> captor =
                ArgumentCaptor.forClass((Class) QueryWrapper.class);
        verify(mapper, times(times)).selectList(captor.capture());
        return captor.getAllValues();
    }

    private QueryWrapper<com.dark.javaHarness.domain.entity.ToolCallLogEntity> capturedWrapper() {
        return capturedWrappers(1).get(0);
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

    /* ---------------- 过滤条件 ---------------- */

    @Test
    void list_sessionIdAndServerName_addedAsEqConditions() {
        when(mapper.selectList(any())).thenReturn(List.of());

        controller.list("s1", "tavily", 50);

        QueryWrapper<com.dark.javaHarness.domain.entity.ToolCallLogEntity> qw = capturedWrapper();
        String segment = qw.getSqlSegment();
        assertTrue(segment.contains("session_id"), "应含 session_id 条件，实际：" + segment);
        assertTrue(segment.contains("server_name"), "应含 server_name 条件，实际：" + segment);
        assertTrue(qw.getParamNameValuePairs().containsValue("s1"));
        assertTrue(qw.getParamNameValuePairs().containsValue("tavily"));
    }

    @Test
    void list_noParams_noFilterConditions() {
        when(mapper.selectList(any())).thenReturn(List.of());

        controller.list(null, null, 50);

        QueryWrapper<com.dark.javaHarness.domain.entity.ToolCallLogEntity> qw = capturedWrapper();
        String segment = qw.getSqlSegment();
        assertFalse(segment.contains("session_id"), "无参不应带 session_id 条件");
        assertFalse(segment.contains("server_name"), "无参不应带 server_name 条件");
        assertTrue(segment.contains("ORDER BY id DESC"), "应按 id 倒序，实际：" + segment);
        assertEquals(" LIMIT 50", lastSql(qw), "默认 limit 50");
    }

    /* ---------------- limit 收敛 ---------------- */

    @Test
    void list_limitBounds_clamped() {
        when(mapper.selectList(any())).thenReturn(List.of());

        controller.list(null, null, 0);
        controller.list(null, null, 10_000);

        List<QueryWrapper<com.dark.javaHarness.domain.entity.ToolCallLogEntity>> wrappers = capturedWrappers(2);
        assertEquals(" LIMIT 1", lastSql(wrappers.get(0)), "下限收敛为 1");
        assertEquals(" LIMIT 200", lastSql(wrappers.get(1)), "上限收敛为 200");
    }

    @Test
    void list_blankParams_treatedAsAbsent() {
        when(mapper.selectList(any())).thenReturn(List.of());

        controller.list("  ", "", 50);

        QueryWrapper<com.dark.javaHarness.domain.entity.ToolCallLogEntity> qw = capturedWrapper();
        String segment = qw.getSqlSegment();
        assertFalse(segment.contains("session_id"), "空白 sessionId 视为未传");
        assertFalse(segment.contains("server_name"), "空白 serverName 视为未传");
    }
}
