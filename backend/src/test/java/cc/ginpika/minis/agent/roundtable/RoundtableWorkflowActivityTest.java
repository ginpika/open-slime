package cc.ginpika.minis.agent.roundtable;

import cc.ginpika.minis.agent.member.AgentDirectory;
import cc.ginpika.minis.agent.member.AgentDtos;
import cc.ginpika.minis.agent.member.AgentStore.AgentRow;
import cc.ginpika.minis.ai.core.BuiltinAgents;
import cc.ginpika.minis.ai.runtime.AgentActivitySource;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RoundtableWorkflowActivityTest {
    private final AgentDirectory directory=mock(AgentDirectory.class);
    private final RoundtableChatService chat=mock(RoundtableChatService.class);
    private final RoundtableWorkRuntime runtime=mock(RoundtableWorkRuntime.class);
    private final AgentActivitySource activity=mock(AgentActivitySource.class);
    private final RoundtableWorkflowService service=new RoundtableWorkflowService(chat,directory,
            mock(RoundtableTodoStore.class),mock(TopicStateMachine.class),runtime,
            mock(RoundtableWorkRunStore.class),new ObjectMapper(),List.of(activity));
    private final AgentRow coding=new AgentRow(BuiltinAgents.CODING,"编码 Agent",true,"[]","[]",null,1,null,null,null,0,0,null,null);

    @Test void globalOverviewShowsCodingWorkAndCountsWithoutRoundtableWorker() {
        when(directory.peers()).thenReturn(List.of(coding));
        when(activity.activities("alice",null)).thenReturn(List.of(new AgentActivitySource.Activity(coding.id(),1,2,3)));
        var result=service.overview("alice").agents().getFirst();
        assertTrue(result.working());assertEquals("RUNNING",result.execution());
        assertEquals(1,result.pending());assertEquals(2,result.doing());assertEquals(3,result.done());
        when(activity.activities("alice",null)).thenReturn(List.of(new AgentActivitySource.Activity(coding.id(),0,0,3)));
        assertFalse(service.overview("alice").agents().getFirst().working());
    }

    @Test void missingIdentityDoesNotQueryPrivateActivity() {
        when(directory.peers()).thenReturn(List.of(coding));
        assertFalse(service.overview((String)null).agents().getFirst().working());
        verifyNoInteractions(activity);
    }

    @Test void taskViewIncludesOnlyCodingWorkLinkedToTheSelectedSession() {
        when(chat.sessions()).thenReturn(List.of(new AgentDtos.SessionView("session-1","task",null,null,null,null,List.of(coding.id()),0,0L)));
        when(directory.peer(coding.id())).thenReturn(java.util.Optional.of(coding));
        when(activity.activities("alice","session-1")).thenReturn(List.of(new AgentActivitySource.Activity(coding.id(),0,1,0)));
        var agent=service.view("session-1","alice").agents().getFirst();
        assertTrue(agent.working());assertEquals("RUNNING",agent.execution());
        verify(activity).activities("alice","session-1");verify(activity,never()).activities(anyString(),isNull());
    }
}
