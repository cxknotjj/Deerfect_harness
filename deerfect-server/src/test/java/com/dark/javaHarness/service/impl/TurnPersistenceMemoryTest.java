package com.dark.javaHarness.service.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dark.javaHarness.contract.api.AgentService;
import com.dark.javaHarness.contract.api.SessionService;
import com.dark.javaHarness.contract.model.Goal;
import com.dark.javaHarness.contract.model.SessionInfo;
import com.dark.javaHarness.memory.MemoryWriteDispatcher;
import com.dark.javaHarness.memory.plugin.MemoryTurn;
import com.dark.javaHarness.service.GoalService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 写入时机：成功轮投递一次，失败轮/无 creator 不投递。
 * spec:001
 */
class TurnPersistenceMemoryTest {

    private SessionService sessionService;
    private AgentService agentService;
    private MemoryWriteDispatcher dispatcher;
    private TurnPersistence persistence;

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionService.class);
        agentService = mock(AgentService.class);
        dispatcher = mock(MemoryWriteDispatcher.class);
        persistence = new TurnPersistence(sessionService, agentService, mock(GoalService.class), dispatcher);
    }

    @Test
    void succeededTurnDispatchesOnce() {
        when(dispatcher.active()).thenReturn(true);
        when(sessionService.getSession("s1"))
                .thenReturn(new SessionInfo(1L, "n", "cli", "q", 1, null));
        when(agentService.findAgentNameById(1L)).thenReturn(Optional.of("general"));
        Goal goal = Goal.newGoal("g1", "hi", "s1");
        goal.succeed("ok");

        persistence.writeBackSucceededTurn("s1", "我叫小林", goal, "记住了", 1L);

        verify(dispatcher).submit(argThat((MemoryTurn turn) ->
                "cli".equals(turn.scope().userId())
                        && "general".equals(turn.scope().agentName())
                        && "我叫小林".equals(turn.userMessage())
                        && "记住了".equals(turn.assistantReply())));
    }

    @Test
    void failedTurnDoesNotDispatch() {
        Goal goal = Goal.newGoal("g1", "hi", "s1");
        goal.fail("boom");

        persistence.writeBackSucceededTurn("s1", "hi", goal, "reply", 1L);

        verify(dispatcher, never()).submit(any());
    }

    @Test
    void streamedReplyDispatchesOnce() {
        when(dispatcher.active()).thenReturn(true);
        when(sessionService.getSession("s1"))
                .thenReturn(new SessionInfo(1L, "n", "cli", "q", 1, null));
        when(agentService.findAgentNameById(1L)).thenReturn(Optional.of("general"));

        persistence.writeBackReply("s1", "hi", "reply", 1L);

        verify(dispatcher).submit(any(MemoryTurn.class));
    }

    @Test
    void missingCreatorSkipsDispatch() {
        when(dispatcher.active()).thenReturn(true);
        when(sessionService.getSession("s1"))
                .thenReturn(new SessionInfo(1L, "n", " ", "q", 1, null));

        persistence.writeBackReply("s1", "hi", "reply", 1L);

        verify(dispatcher, never()).submit(any());
    }

    @Test
    void disabledPluginSkipsWithoutIdentityLookup() {
        when(dispatcher.active()).thenReturn(false);

        persistence.writeBackReply("s1", "hi", "reply", 1L);

        verify(dispatcher, never()).submit(any());
        verify(sessionService, never()).getSession(any());
    }
}
