package cc.ginpika.minis.agent.roundtable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cc.ginpika.minis.agent.chat.ChatExecution;
import cc.ginpika.minis.agent.chat.ChatStream;
import cc.ginpika.minis.agent.chat.ChatTurnRunner;
import cc.ginpika.minis.agent.chat.dto.ChatDtos;
import cc.ginpika.minis.agent.chat.store.HubSessionStore.HubRow;
import cc.ginpika.minis.agent.member.AgentDirectory;
import cc.ginpika.minis.agent.member.AgentStore.AgentRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.springframework.test.util.ReflectionTestUtils;

/** Exercise the actual speak ledger, ask_user handler and durable reply writer together. */
class RoundtableSpeechPersistenceTest {
    @Spy ObjectMapper om = new ObjectMapper();
    @Mock ChatTurnRunner runner;
    @Mock AgentDirectory directory;
    @Mock RoundtableTopicStore topics;
    @Mock RoundtableSessionStore sessions;
    @Mock RoundtableMessageStore messages;
    @Mock RoundtableProgress progress;
    @Mock RoundtableRevision revisions;
    @Mock TopicStateMachine machine;
    @Mock RoundtableContextBuilder context;
    @Mock CollaborationWorkStatus workStatus;
    @Mock CollaborationQuality quality;
    @Mock CollaborationPlan plans;
    @Mock RoundtableTaskTools taskTools;
    @Mock CollaborationWorkspaceBinding workspaceBinding;
    @Mock cc.ginpika.minis.ai.computer.terminal.TerminalService terminal;
    @Mock cc.ginpika.minis.ai.core.tool.ToolManager tools;
    @Mock cc.ginpika.minis.config.ContextProps contextProps;
    @InjectMocks RoundtableChatService chat;
    AutoCloseable mocks;
    @BeforeEach void init() { mocks = MockitoAnnotations.openMocks(this); }
    @AfterEach void close() throws Exception { mocks.close(); }

    @SuppressWarnings("unchecked")
    @Test void coreReceivesManagementAndTerminalToolsWithoutMemberGrants() {
        var session = (ThreadLocal<String>) ReflectionTestUtils.getField(chat, "speakSession");
        var peer = (ThreadLocal<String>) ReflectionTestUtils.getField(chat, "speakPeer");
        var revision = (ThreadLocal<Integer>) ReflectionTestUtils.getField(chat, "speakBaseRevision");
        var conversation = (ThreadLocal<String>) ReflectionTestUtils.getField(chat, "conversationInput");
        session.set("session"); peer.set(AgentDirectory.BUILTIN_ID); revision.set(0);
        try {
            when(progress.allowed("session")).thenReturn(true);
            var all = om.createArrayNode();
            for (String name : java.util.List.of("create_plan", "delete_node", "run_command", "delegate_subagent"))
                all.addObject().putObject("function").put("name", name);
            when(tools.agentToolsOf(cc.ginpika.minis.ai.core.tool.ToolSurface.CHAT)).thenReturn(all);
            var empty = om.createArrayNode();
            when(tools.toolsOf(anyList(), any())).thenReturn(empty);
            when(tools.grantedToolsOf(anyList(), any())).thenReturn(empty);
            var plan = mock(CollaborationPlan.Plan.class);
            when(plan.coordinatorId()).thenReturn(AgentDirectory.BUILTIN_ID);
            when(plans.find("session")).thenReturn(Optional.of(plan));
            var exposed = ReflectionTestUtils.<com.fasterxml.jackson.databind.node.ArrayNode>invokeMethod(chat, "toolSchemas");
            assertEquals(all, exposed, "managed sessions must not apply worker tool restrictions to the core");
            var call = om.createObjectNode(); call.putObject("function").put("name", "run_command");
            when(workspaceBinding.terminal(call, "session", null, terminal)).thenReturn("actual result");
            assertEquals("actual result", chat.runTool(call, "batch", "session"));
            verify(workspaceBinding).terminal(call, "session", null, terminal);
            verifyNoInteractions(runner);
            var management = om.createObjectNode(); management.putObject("function").put("name", "create_plan");
            when(runner.runTool(management, "batch", "session")).thenReturn("plan created");
            assertEquals("plan created", chat.runTool(management, "batch", "session"));
            conversation.set("question");
            assertEquals(all, ReflectionTestUtils.invokeMethod(chat, "toolSchemas"), "core tools remain available during a direct question");
        } finally { session.remove(); peer.remove(); revision.remove(); conversation.remove(); }
    }

    @SuppressWarnings("unchecked")
    @Test void managedWorkUsesLiveWorkspaceGrantsAndRejectsStaleToolResults() {
        var session=(ThreadLocal<String>)ReflectionTestUtils.getField(chat,"speakSession");
        var peerScope=(ThreadLocal<String>)ReflectionTestUtils.getField(chat,"speakPeer");
        var revision=(ThreadLocal<Integer>)ReflectionTestUtils.getField(chat,"speakBaseRevision");
        var work=(ThreadLocal<RoundtableTodoStore.TodoRow>)ReflectionTestUtils.getField(chat,"workTodo");
        session.set("session");peerScope.set("peer");revision.set(0);
        try {
            when(progress.allowed("session")).thenReturn(true);
            var plan=mock(CollaborationPlan.Plan.class);when(plan.coordinatorId()).thenReturn("peer");
            when(plans.find("session")).thenReturn(Optional.of(plan));
            var peer=mock(AgentRow.class);when(directory.peer("peer")).thenReturn(Optional.of(peer));
            when(directory.declaredTerminals(peer)).thenReturn(java.util.List.of("workspace"));
            var agentTools=om.createArrayNode();
            for(String name:java.util.List.of("coding_task","run_command","delegate_subagent"))
                agentTools.addObject().putObject("function").put("name",name);
            when(tools.agentToolsOf(cc.ginpika.minis.ai.core.tool.ToolSurface.CHAT)).thenReturn(agentTools);
            var empty=om.createArrayNode();
            org.mockito.stubbing.Answer<com.fasterxml.jackson.databind.node.ArrayNode> schemas=inv->{
                var array=om.createArrayNode();for(String name:inv.<java.util.List<String>>getArgument(0))array.addObject().putObject("function").put("name",name);return array;
            };
            when(tools.toolsOf(anyList(),any())).thenAnswer(schemas);when(tools.grantedToolsOf(anyList(),any())).thenAnswer(schemas);
            var exposed=ReflectionTestUtils.<com.fasterxml.jackson.databind.node.ArrayNode>invokeMethod(chat,"toolSchemas");
            assertTrue(exposed.toString().contains("coding_task"));assertTrue(exposed.toString().contains("run_command"));assertTrue(exposed.toString().contains("delegate_subagent"));
            var command=om.createObjectNode();command.putObject("function").put("name","run_command");
            when(workspaceBinding.terminal(command,"session",java.util.Set.of("workspace"),terminal)).thenReturn("scoped result");
            assertEquals("scoped result",chat.runTool(command,"batch","session"));
            when(directory.declaredTerminals(peer)).thenReturn(java.util.List.of());
            assertTrue(chat.runTool(command,"batch","session").contains("授权"));
            verify(workspaceBinding,times(1)).terminal(any(),anyString(),any(),any());
            when(directory.declaredTerminals(peer)).thenReturn(java.util.List.of("new-workspace"));
            when(workspaceBinding.terminal(command,"session",java.util.Set.of("new-workspace"),terminal)).thenReturn("migrated result");
            assertEquals("migrated result",chat.runTool(command,"batch","session"));
            verifyNoInteractions(runner);
            var todo=mock(RoundtableTodoStore.TodoRow.class);when(todo.status()).thenReturn("doing");work.set(todo);
            var call=om.createObjectNode();call.putObject("function").put("name","coding_task");
            when(runner.runTool(eq(call),anyString(),anyString())).thenAnswer(inv->{
                when(revisions.current("session")).thenReturn(1);
                return "{\"code\":\"NEEDS_DIAGNOSIS\",\"reason\":\"old evidence\"}";
            });
            assertThrows(ChatStream.ChatCancelledException.class,()->chat.runTool(call,"batch","session"));
            verifyNoInteractions(taskTools);
        } finally {session.remove();peerScope.remove();revision.remove();work.remove();}
    }

    @Test void statusReplyUsesPersonaLiveFactsAndReadOnlyToolsWithoutWorkerCleanup() {
        AgentRow peer = mock(AgentRow.class);
        when(peer.name()).thenReturn("编码 Agent"); when(directory.peer("peer")).thenReturn(Optional.of(peer));
        when(directory.nameOf("peer")).thenReturn("编码 Agent");
        when(context.persona("peer")).thenReturn("保持编码成员的人格");
        when(context.conversationTranscript(eq("session"), anyInt())).thenReturn("用户之前询问了 rAF");
        when(workStatus.describe("session", "peer")).thenReturn("文档任务 RUNNING；最近操作 run_validation [RUNNING]");
        when(directory.declaredTerminals(peer)).thenReturn(java.util.List.of("new-workspace"));
        when(workspaceBinding.context("session")).thenReturn("本项目目录 /new/project");
        var emptyTools = om.createArrayNode();
        when(tools.agentToolsOf(any())).thenReturn(emptyTools);
        when(tools.toolsOf(anyList(), any())).thenReturn(emptyTools);
        when(tools.grantedToolsOf(anyList(), any())).thenReturn(emptyTools);
        when(messages.insert(anyString(), any(), anyString(), any(), any(), any(), any(), any(), any(), any())).thenReturn(11L);
        when(runner.chatStream(eq(chat), eq("session"), anyString(), any(), any(), any())).thenAnswer(inv -> {
            var row = mock(HubRow.class); when(row.id()).thenReturn("session");
            var body = chat.requestBody(row, new cc.ginpika.minis.config.ModelTarget("http://fixture", "key", "model"), null, 8192);
            assertTrue(body.path("messages").get(0).path("content").asText().contains("保持编码成员的人格"));
            assertTrue(body.toString().contains("run_validation [RUNNING]"));
            assertTrue(body.toString().contains("new-workspace"));
            assertTrue(body.toString().contains("/new/project"));
            assertEquals("现在忙什么？", body.path("messages").get(2).path("content").asText());
            assertEquals(0, body.path("tools").size());
            assertFalse(chat.settleTodosOnFinish());
            var forbidden = om.createObjectNode(); forbidden.putObject("function").put("name", "start_work");
            assertTrue(chat.runTool(forbidden, "batch", "session").contains("未执行"));
            ChatStream sink = inv.getArgument(5); sink.delta(null, "正在验证文档，工作继续。");
            chat.recordReply(row, "正在验证文档，工作继续。", null, null, null);
            return new ChatDtos.ChatReply("session", 11L, "正在验证文档，工作继续。", null, true, null);
        });
        var reply = chat.converse("session", "peer", "现在忙什么？", new ChatStream() {});
        assertEquals(java.util.List.of("正在验证文档，工作继续。"), reply.utterances());
        assertTrue(chat.settleTodosOnFinish(), "conversation scope must be removed after reply");
        verifyNoInteractions(machine);
        verify(runner, never()).requestStop(anyString());
        verifyNoInteractions(progress);
    }

    @Test void spokenQuestionRemainsPersistableWhenAgentAsksUserToDecide() {
        when(topics.topic("topic")).thenReturn(Optional.of(new RoundtableTopicStore.TopicRow(
                "topic", "session", "user", null, "open", null, 0, 1, null)));
        AgentRow peer = mock(AgentRow.class);
        when(peer.name()).thenReturn("规划成员"); when(directory.peer("peer")).thenReturn(Optional.of(peer));
        when(directory.nameOf("peer")).thenReturn("规划成员"); when(sessions.findById("session")).thenReturn(Optional.empty());
        when(messages.insert(anyString(), any(), anyString(), any(), any(), any(), any(), any(), any(), any())).thenReturn(10L);
        when(runner.chatStream(eq(chat), eq("session"), anyString(), any(), any(), any())).thenAnswer(inv -> {
            ChatExecution exec = inv.getArgument(0);
            var speech = om.createObjectNode();
            speech.putObject("function").put("arguments", "{\"text\":\"是否按最小版本交付？\"}");
            ReflectionTestUtils.invokeMethod(chat, "speakTool", speech);
            var ask = om.createObjectNode(); ask.putObject("function").put("arguments", "{\"reason\":\"请确认交付范围\"}");
            ReflectionTestUtils.invokeMethod(chat, "stateTool", "ask_user", ask);
            assertEquals("是否按最小版本交付？", exec.replyOnCancel(""));
            var row = mock(HubRow.class); when(row.id()).thenReturn("session");
            exec.recordReply(row, "", null, null, null);
            return new ChatDtos.ChatReply("session", 0L, "", null, true, null);
        });
        var result = chat.speak("topic", "peer", new ChatStream() {});
        assertEquals(java.util.List.of("是否按最小版本交付？"), result.utterances());
        assertEquals(10L, result.messageId());
        verify(messages).insert(eq("session"), eq("topic"), eq("peer"), eq("peer"), eq("规划成员"),
                eq("是否按最小版本交付？"), isNull(), isNull(), isNull(), isNull());
        verify(runner).requestStopOthers("session");
        verify(runner, never()).requestStop(anyString());
        verify(progress).pause("session", "请确认交付范围");
        verify(machine).askUser("topic", "peer", "请确认交付范围");
    }

    @Test void conversationCanSubmitAControlRequestFromCurrentUserTextWithoutExecutingItDuringGeneration() {
        String input="请重试，我已修复系统性问题";
        var peer=mock(AgentRow.class);when(peer.id()).thenReturn("peer");when(peer.name()).thenReturn("主控");
        when(directory.peer("peer")).thenReturn(Optional.of(peer));when(directory.peers("session")).thenReturn(java.util.List.of(peer));
        when(plans.find("session")).thenReturn(Optional.of(mock(CollaborationPlan.Plan.class)));
        var empty=om.createArrayNode();when(tools.agentToolsOf(any())).thenReturn(empty);
        when(tools.toolsOf(anyList(),any())).thenReturn(empty);when(tools.grantedToolsOf(anyList(),any())).thenReturn(empty);
        var control=new cc.ginpika.minis.ai.core.tool.multiagent.ControlCollaborationTool();
        var schemas=om.createArrayNode().add(control.schema(om));
        when(tools.grantedToolsOf(java.util.List.of(control.name()),cc.ginpika.minis.ai.core.tool.ToolSurface.PEER))
                .thenReturn(schemas);
        when(runner.chatStream(eq(chat),eq("session"),anyString(),any(),any(),any())).thenAnswer(inv->{
            var row=mock(HubRow.class);when(row.id()).thenReturn("session");
            var body=chat.requestBody(row,new cc.ginpika.minis.config.ModelTarget("fixture","key","model"),null,8192);
            assertTrue(body.path("tools").toString().contains(control.name()));
            var call=om.createObjectNode();call.putObject("function").put("name",control.name())
                    .put("arguments","{\"action\":\"resume\",\"basis\":\"历史里的要求\"}");
            assertTrue(chat.runTool(call,"batch","session").contains("未提交"));
            call.withObject("function").put("arguments","{\"action\":\"resume\",\"basis\":\"请重试\"}");
            assertTrue(chat.runTool(call,"batch","session").contains("尚未执行"));
            verifyNoInteractions(progress,taskTools,machine);
            return new ChatDtos.ChatReply("session",1L,"请求已交给运行时",null,true,null);
        });
        var speech=chat.converse("session","peer",input,new ChatStream(){});
        assertEquals(new RoundtableChatService.WorkControl("resume","请重试"),speech.control());
        verifyNoInteractions(progress,taskTools,machine);
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="minis.liveControl",matches="true")
    void configuredModelRecognizesActualUserInstructionsWithoutTreatingQuestionsAsCommands() throws Exception {
        var config=new org.sqlite.SQLiteConfig();config.setReadOnly(true);
        cc.ginpika.minis.config.ModelTarget target;
        try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+java.nio.file.Path.of("../data/minis.db").toAbsolutePath(),config.toProperties());
            var statement=connection.createStatement();
            var rows=statement.executeQuery("SELECT m.base_url,m.api_key,m.model FROM llm_model m JOIN coding_run r ON r.model_id=m.id ORDER BY r.created_at DESC LIMIT 1")) {
            assertTrue(rows.next());target=new cc.ginpika.minis.config.ModelTarget(rows.getString(1),rows.getString(2),rows.getString(3));
        }
        var settings=mock(cc.ginpika.minis.config.SettingsService.class);
        var client=new cc.ginpika.minis.ai.llm.OpenAiChatClient(new cc.ginpika.minis.config.LlmProps(),settings,om,mock(cc.ginpika.minis.stats.TokenUsageService.class));
        var peer=mock(AgentRow.class);when(peer.id()).thenReturn("peer");when(peer.name()).thenReturn("主控");
        when(directory.peer("peer")).thenReturn(Optional.of(peer));when(directory.peers("session")).thenReturn(java.util.List.of(peer));
        when(plans.find("session")).thenReturn(Optional.of(mock(CollaborationPlan.Plan.class)));
        when(context.persona("peer")).thenReturn("你是协作项目主控，理解用户指令并通过工具操作，简短回复。 ");
        when(context.conversationTranscript(eq("session"),anyInt())).thenReturn("原目标：完成游戏入口和主界面。服务器停机后已迁移，工作未交付；用户授权继续原目标。");
        when(workStatus.describe("session","peer")).thenReturn("项目已绑定新工作目录，成员已有工作空间权限；计划 planning，继续状态 paused；原条目 blocked/dropped。");
        when(directory.declaredTerminals(peer)).thenReturn(java.util.List.of("new-workspace"));
        when(workspaceBinding.context("session")).thenReturn("当前项目绑定 new-workspace /new/project，仅在此目录工作。");
        var empty=om.createArrayNode();when(tools.agentToolsOf(any())).thenReturn(empty);
        when(tools.toolsOf(anyList(),any())).thenReturn(empty);when(tools.grantedToolsOf(anyList(),any())).thenReturn(empty);
        var control=new cc.ginpika.minis.ai.core.tool.multiagent.ControlCollaborationTool();var schemas=om.createArrayNode().add(control.schema(om));
        when(tools.grantedToolsOf(java.util.List.of(control.name()),cc.ginpika.minis.ai.core.tool.ToolSurface.PEER)).thenReturn(schemas);
        when(runner.chatStream(eq(chat),eq("session"),anyString(),any(),any(),any())).thenAnswer(inv->{
            var row=mock(HubRow.class);when(row.id()).thenReturn("session");
            var body=chat.requestBody(row,target,null,16384);
            var raw=client.chatRaw(target,body,null,"collaboration");
            for(var call:raw.message().path("tool_calls"))assertTrue(chat.runTool(call,"batch","session").contains("尚未执行"));
            return new ChatDtos.ChatReply("session",1L,"已检查当前指令",null,true,null);
        });
        var cases=new java.util.LinkedHashMap<String,String>();
        cases.put("服务器已迁移，请尝试恢复工作","resume");
        cases.put("把所有放弃的内容加入到待办中，并重新排列任务优先级","replan");
        cases.put("请重试，我已修复系统性问题","resume");
        cases.put("让编码Agent停止工作吧，服务器down了","stop");
        cases.put("现在进展到哪一步了？",null);
        cases.put("你是不是用了关键词检测？",null);
        cases.put("如果恢复工作会发生什么？先解释，别执行。",null);
        for(var sample:cases.entrySet()) {
            var speech=chat.converse("session","peer",sample.getKey(),new ChatStream(){});
            assertEquals(sample.getValue(),speech.control()==null?null:speech.control().action(),sample.getKey());
            System.out.println("LIVE_CONTROL "+(sample.getValue()==null?"conversation":sample.getValue())+": passed");
        }
        verifyNoInteractions(progress,taskTools,machine);
    }
}
