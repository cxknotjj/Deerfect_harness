package com.dark.javaHarness.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.domain.dto.SessionMessagesView;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.SessionService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * HarnessController 会话接口单测：
 * POST /api/harness/sessions 新建会话、POST /api/harness/sessions/{id}/agent 切换 Agent
 * 均为纯转发——确认参数传递与返回视图组装。
 */
@ExtendWith(MockitoExtension.class)
class HarnessControllerTest {

    @Mock
    private AgentService agentService;

    @Mock
    private SessionService sessionService;

    @InjectMocks
    private HarnessController controller;

    @Test
    void createSession_returnsNewSessionId() {
        when(sessionService.createSession("cli", "新会话")).thenReturn("51");

        var view = controller.createSession("新会话");

        assertEquals("51", view.sessionId(), "应返回服务端新建的会话 ID");
        assertEquals("新会话", view.sessionName(), "应回显占位会话名");
        verify(sessionService).createSession("cli", "新会话");
    }

    @Test
    void sessionMessages_delegatesAndWrapsItems() {
        when(sessionService.listMessages("9"))
                .thenReturn(List.of(new SessionMessagesView.Item("user", "你好"),
                        new SessionMessagesView.Item("assistant", "在的")));

        var view = controller.sessionMessages("9");

        verify(sessionService).listMessages("9");
        assertEquals("9", view.sessionId());
        assertEquals(2, view.messages().size(), "应原样透传历史消息");
        assertEquals("assistant", view.messages().get(1).role());
        assertEquals("在的", view.messages().get(1).content());
    }

    @Test
    void switchAgent_delegatesAndReturnsView() {
        when(agentService.findAgentNameById(3L)).thenReturn(Optional.of("deepseek"));

        var view = controller.switchAgent("9", 3L);

        verify(sessionService).switchAgent("9", 3L);
        assertEquals("9", view.sessionId());
        assertEquals(3L, view.agentId());
        assertEquals("deepseek", view.agentName(), "应回显 agentId 对应的 agent 名");
    }

    @Test
    void deleteSession_delegates() {
        controller.deleteSession("9");

        verify(sessionService).deleteSession("9");
    }
}
