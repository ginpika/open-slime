package cc.ginpika.minis.agent.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cc.ginpika.minis.agent.chat.ChatContextManifests;
import cc.ginpika.minis.agent.chat.ChatContextSources;
import cc.ginpika.minis.agent.chat.store.ChatMessageStore;
import cc.ginpika.minis.agent.chat.store.HubSessionStore;
import cc.ginpika.minis.agent.chat.turn.TurnStore;
import cc.ginpika.minis.agent.chat.turn.TurnTrigger;
import cc.ginpika.minis.agent.member.AgentDirectory;
import cc.ginpika.minis.agent.runtime.AgentRunner;
import cc.ginpika.minis.agent.runtime.AgentRuntime;
import cc.ginpika.minis.agent.runtime.ChangeSetStore;
import cc.ginpika.minis.agent.runtime.ExecStore;
import cc.ginpika.minis.agent.runtime.TaskStore;
import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.core.MemoryIdentity;
import cc.ginpika.minis.ai.core.MemoryScopeCtx;
import cc.ginpika.minis.ai.core.prompt.PromptManager;
import cc.ginpika.minis.ai.core.tool.ToolManager;
import cc.ginpika.minis.ai.core.tool.ToolRisk;
import cc.ginpika.minis.ai.core.tool.ToolValidator;
import cc.ginpika.minis.ai.knowledge.ingest.KnowledgeToolbox;
import cc.ginpika.minis.ai.memory.MemoryIngest;
import cc.ginpika.minis.ai.memory.MemoryWriter;
import cc.ginpika.minis.config.ModelTarget;
import cc.ginpika.minis.config.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class ScopedSubagentRunTest {

    @Test
    void registeredChildAssemblesOnlyItsGoalAuthorizedReferencesAndTools() throws Exception {
        var database = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        try {
            new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(database);
            var jdbc = new JdbcTemplate(database);
            var om = new ObjectMapper();
            var tasks = new TaskStore(jdbc);
            var execs = new ExecStore(jdbc);
            var sessions = new HubSessionStore(jdbc);
            var turns = new TurnStore(jdbc);
            var messages = new ChatMessageStore(jdbc);
            var sources = new ChatContextSources(jdbc, om);
            var scope = new SubagentScope(jdbc);
            var settings = mock(SettingsService.class);
            var runtime = mock(AgentRuntime.class);
            var memory = mock(MemoryWriter.class);
            var ingest = mock(MemoryIngest.class);
            var runner = new AgentRunner(settings, runtime, execs, tasks, mock(ChangeSetStore.class), om,
                    memory, ingest, mock(ToolValidator.class));
            var prompts = mock(PromptManager.class);
            var tools = new ToolManager(List.of(SubagentDelegationTest.tool("read_document", ToolRisk.READ),
                    SubagentDelegationTest.tool("web_browser", ToolRisk.READ),
                    SubagentDelegationTest.tool("recall_memory", ToolRisk.READ),
                    SubagentDelegationTest.tool("run_command", ToolRisk.EXEC)), om);
            var delegate = new ScopedSubagentRun(runner, tasks, scope, sources, new ChatContextManifests(sessions, turns),
                    settings, prompts, tools, mock(KnowledgeToolbox.class), om);

            String agent = AgentDirectory.BUILTIN_ID;
            String sessionId = "parent-session";
            sessions.insert(sessionId, "委派", null);
            long unrelated = messages.insert(sessionId, "user", "不属于子任务的原会话内容", null, null, null, null, null);
            long evidence = messages.insert(sessionId, "user", "重要的原始证据", null, null, null, null, null);
            long task = tasks.insert(TaskStore.KIND_SESSION, null, "父任务", sessionId, agent, "raw, non-JSON input");
            tasks.setStatus(task, TaskStore.ST_RUNNING);
            long turn = turns.begin(sessionId, TurnTrigger.Source.USER_INPUT, evidence, task);
            var parent = MemoryExec.chat(agent, null, sessionId, task, Long.toString(turn), MemoryIdentity.INFERRED);
            var session = sessions.findById(sessionId).orElseThrow();
            var args = om.createObjectNode().put("instruction", "核查已授权的资料");
            args.putArray("source_refs").add("message:" + evidence);
            when(settings.chatModelId(any())).thenReturn("model-id");
            when(settings.chatContextWindow("model-id")).thenReturn(12_000);
            when(settings.chatOutputTokens("model-id")).thenReturn(512);
            when(settings.resolveChatModel("model-id")).thenReturn(new ModelTarget("http://test", "key", "model"));

            var handoff = delegate.capture(args, session, parent, "actor", "核查资料", List.of());
            long child = delegate.register(handoff, null, task);
            assertTrue(scope.visible(child, sessionId, agent, null));
            when(runtime.run(any())).thenAnswer(invocation -> {
                AgentRuntime.Spec spec = invocation.getArgument(0);
                assertEquals(child, MemoryScopeCtx.get().taskId());
                assertEquals(task, tasks.task(child).orElseThrow().parentId());
                var prepared = spec.preparation().prepare(0, spec.body());
                String body = prepared.body().toString();
                assertEquals(512, prepared.body().path("max_tokens").asInt());
                assertTrue(body.contains("核查已授权的资料"));
                assertTrue(body.contains("重要的原始证据"));
                assertFalse(body.contains("不属于子任务的原会话内容"));
                assertTrue(body.contains("read_document"));
                assertFalse(body.contains("run_command"));
                assertFalse(body.contains("web_browser"));
                assertFalse(body.contains("recall_memory"));

                var read = om.createObjectNode();
                var fn = read.putObject("function").put("name", ChatContextSources.TOOL_NAME);
                fn.put("arguments", "{\"action\":\"read\",\"source\":\"message:" + unrelated + "\"}");
                assertTrue(spec.dispatch().run(read).contains("不在本次授权范围"));
                fn.put("arguments", "{\"action\":\"read\",\"source\":\"message:" + evidence + "\"}");
                assertTrue(spec.dispatch().run(read).contains("重要的原始证据"));
                return new AgentRuntime.Result("子代理完整报告", null, AgentRuntime.End.ANSWERED, 0);
            });
            assertEquals("done", delegate.run(handoff, null, child).status());
            assertEquals("子代理完整报告", execs.exec(child).orElseThrow().output());
            assertEquals("子代理完整报告", scope.report(child, sessionId, agent, null).orElseThrow().content());
            verifyNoInteractions(memory, ingest);

            jdbc.update("INSERT INTO memory_retracted_source(ref, retracted_at) VALUES(?, 1)", "chat_message:" + evidence);
            assertFalse(scope.visible(child, sessionId, agent, null));
            assertTrue(scope.report(child, sessionId, agent, null).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> delegate.run(handoff, null, child));
        } finally {
            database.destroy();
        }
    }
}
