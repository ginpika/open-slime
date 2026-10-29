package cc.ginpika.minis.agent.roundtable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cc.ginpika.minis.agent.member.AgentDirectory;
import cc.ginpika.minis.agent.member.AgentDtos;
import cc.ginpika.minis.agent.member.AgentStore.AgentRow;
import cc.ginpika.minis.agent.runtime.event.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Real SQLite and event routing; only model generation is replaced by deterministic answers. */
class RoundtableAutonomyTest {
    @TempDir Path temp;
    private final ObjectMapper om = new ObjectMapper();
    private final java.util.List<RoundtableWorkRuntime> workers = new java.util.ArrayList<>();
    @org.junit.jupiter.api.AfterEach void closeWorkers() { workers.forEach(RoundtableWorkRuntime::close); }

    class Fixture {
        final JdbcTemplate jdbc;
        final RoundtableTodoStore board;
        final RoundtableSessionStore sessions;
        final RoundtableMessageStore messages;
        final RoundtableProgress progress;
        final CollaborationPlan plans;
        final CollaborationStore collaboration;
        final RuntimeEventBus events;
        final AgentDirectory directory = mock(AgentDirectory.class);
        final RoundtableChatService chat = mock(RoundtableChatService.class);
        final RoundtableDiscussionRuntime discussions = mock(RoundtableDiscussionRuntime.class);
        final RoundtableTaskTools tools;
        final CollaborationScopeGuard scopeGuard=mock(CollaborationScopeGuard.class);
        final cc.ginpika.minis.coding.CodingFacade coding=mock(cc.ginpika.minis.coding.CodingFacade.class);
        final CollaborationQuality quality;
        final String session;
        Fixture() throws Exception {
            var ds = new DriverManagerDataSource("jdbc:sqlite:" + temp.resolve(java.util.UUID.randomUUID() + ".db"));
            try (var connection = ds.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
            }
            jdbc = new JdbcTemplate(ds);
            quality=spy(new CollaborationQuality(jdbc,om));
            board = new RoundtableTodoStore(jdbc);
            sessions = new RoundtableSessionStore(jdbc);
            messages = new RoundtableMessageStore(jdbc);
            progress = new RoundtableProgress(jdbc);
            plans = new CollaborationPlan(jdbc, new cc.ginpika.minis.project.ProjectKnowledgeStore(jdbc));
            collaboration = new CollaborationStore(jdbc);
            events = new RuntimeEventBus(new RuntimeEventStore(jdbc, om),
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
            session = sessions.insert("[\"a\",\"b\"]");
            var a = mock(AgentRow.class); var b = mock(AgentRow.class);
            when(a.id()).thenReturn("a"); when(a.name()).thenReturn("甲");
            when(b.id()).thenReturn("b"); when(b.name()).thenReturn("乙");
            when(directory.peers(anyString())).thenReturn(List.of(a, b));
            when(directory.peer("a")).thenReturn(Optional.of(a));
            when(directory.peer("b")).thenReturn(Optional.of(b));
            when(directory.nameOf(anyString())).thenAnswer(i -> i.getArgument(0));
            when(scopeGuard.check(anyString(),anyString(),anyString())).thenReturn("");
            when(coding.collaborationSubmission(any(),anyString(),anyString(),anyString())).thenReturn("");
            when(coding.applyCollaboration(any(),anyString(),anyString(),anyString())).thenReturn("");
            tools = new RoundtableTaskTools(om, board, directory, events, collaboration,
                    new CollaborationIssueActions(collaboration, board, messages, sessions, progress, events, plans), plans, messages,scopeGuard,coding,quality,new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds)),new cc.ginpika.minis.project.ProjectKnowledgeStore(jdbc));
        }
        String add() { return board.add(session, null, "a", "产出可验证结果", "需要测试通过", "a", null); }
        String call(String name, String peer, RoundtableTodoStore.TodoRow current, Object... arguments) throws Exception {
            var args = om.createObjectNode();
            for (int n = 0; n < arguments.length; n += 2) args.set((String) arguments[n], om.valueToTree(arguments[n + 1]));
            if("manage_plan".equals(name) && "revise".equals(args.path("action").asText())) {
                args.put("deliverables","本次可验证结果").put("exclusions","不扩张到后续目标").put("baseline","已读取当前文件与已有验证结果");
            }
            var call = om.createObjectNode();
            call.putObject("function").put("arguments", args.toString());
            return tools.runTool(name, call, session, peer, null, current);
        }
        RoundtableWorkRuntime worker() {
            var worker = new RoundtableWorkRuntime(chat, board, directory, events, messages,
                    mock(RoundtableLiveBus.class), progress, plans);
            workers.add(worker); return worker;
        }
        String status(String id) { return board.todo(id).orElseThrow().status(); }
        RoundtableDiscussionRuntime runtime() {
            var topicStore = new RoundtableTopicStore(jdbc);
            var machine = new TopicStateMachine(topicStore, new RoundtableTopicEventStore(jdbc));
            var props = new cc.ginpika.minis.config.RoundtableProps();
            var picker = new SpeakerPicker(List.of(new RotationSpeakerStrategy(topicStore)), props);
            var topics = new RoundtableTopicService(sessions, messages, topicStore, directory, picker, machine, chat, props, board);
            return new RoundtableDiscussionRuntime(topics, chat, new RoundtableRevision(), events, new RoundtableAgenda(),
                    mock(RoundtableLiveBus.class), mock(SpeechPacer.class), sessions, directory, picker, machine,
                    messages, board, progress, plans, collaboration);
        }
    }

    @Test void oneAgentProfileExecutesTwoExplicitAssignmentsConcurrentlyAndReportsOnce() throws Exception {
        var f=new Fixture();String first=f.add(),second=f.add();
        String downstream=f.board.add(f.session,null,"a","未派发下游","等待上游后由主控决定","a",om.writeValueAsString(List.of(first,second)));
        f.plans.create(f.session,"a");f.call("manage_plan","a",null,"action","revise","criteria","两项通过独立检查","note","两个互不依赖的产出");
        String receipt=f.call("start_work","a",null,"assignments",List.of(java.util.Map.of("id",first,"reviewer","b"),java.util.Map.of("id",second,"reviewer","b")),"note","独立产出与副本，逐项检查整合");
        assertTrue(receipt.contains("已授权"),receipt);assertEquals(2,f.plans.assignments(f.session).size());
        var started=new CountDownLatch(2);var release=new CountDownLatch(1);var reports=new AtomicInteger();
        f.events.subscribeAll(row->{if("AGENT_REPORT".equals(row.type()) && "runtime".equals(row.source()))reports.incrementAndGet();return true;});
        when(f.chat.executeTodo(anyString(),anyString(),any(),isNull())).thenAnswer(inv->{
            var current=inv.getArgument(2,RoundtableTodoStore.TodoRow.class);String peer=inv.getArgument(1);
            if("checking".equals(current.status()))assertTrue(f.call("review_todo",peer,current,"id",current.id(),"accept",true,"evidence","独立检查通过").contains("检查通过"));
            else {started.countDown();assertTrue(release.await(8,TimeUnit.SECONDS));assertTrue(f.call("complete_todo",peer,current,"id",current.id(),"result","独立成果与验证").contains("提交检查"));}
            return null;
        });
        var worker=f.worker();f.events.publish(RuntimeEvent.of(RuntimeEventType.WORK_STARTED,f.session,"a",java.util.Map.of()));
        assertTrue(started.await(5,TimeUnit.SECONDS),"相同 Agent 配置的两个 TODO 应同时进入执行");
        assertEquals(2,worker.workingCount(f.session));assertTrue(worker.isWorking(f.session,"a"));assertEquals(0,reports.get());
        release.countDown();
        await(()->"done".equals(f.status(first)) && "done".equals(f.status(second)) && worker.workingCount(f.session)==0 && "planning".equals(f.plans.find(f.session).orElseThrow().phase()) && reports.get()==1);
        assertEquals(1,reports.get());assertEquals("pending",f.status(downstream),"不会擅自启动已就绪的下游");
        assertTrue(f.plans.find(f.session).orElseThrow().decision().contains("成果"));
    }

    @Test void anUnreadyItemRejectsTheEntireDispatchBatch() throws Exception {
        var f=new Fixture();String first=f.add();String dependent=f.board.add(f.session,null,"a","依赖上游","不可并发","a",om.writeValueAsString(List.of(first)));
        f.plans.create(f.session,"a");f.call("manage_plan","a",null,"action","revise","criteria","完成工作","note","先检查依赖");
        String receipt=f.call("start_work","a",null,"assignments",List.of(java.util.Map.of("id",first,"reviewer","b"),java.util.Map.of("id",dependent,"reviewer","b")),"note","错误的并行请求");
        assertTrue(receipt.contains("前置依赖"),receipt);
        assertTrue(f.plans.assignments(f.session).isEmpty());assertEquals("planning",f.plans.find(f.session).orElseThrow().phase());
        assertEquals("pending",f.status(first));assertEquals("pending",f.status(dependent));
    }

    @Test void workingAgentRepliesWhileSameAssignmentContinuesThroughIndependentReview() throws Exception {
        var f = new Fixture(); String id = f.add(); f.plans.create(f.session, "b");
        f.plans.revise(f.session, "构建通过", "当前工作");
        f.plans.dispatch(f.session, id, "a", "b", "executing", "原派工");
        int version = f.plans.find(f.session).orElseThrow().version();
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(f.chat.executeTodo(anyString(), anyString(), any(), isNull())).thenAnswer(inv -> {
            var todo = inv.getArgument(2, RoundtableTodoStore.TodoRow.class);
            if ("checking".equals(todo.status())) {
                f.call("review_todo", "b", todo, "id", id, "accept", true, "evidence", "独立复跑通过");
            } else {
                started.countDown(); assertTrue(release.await(6, TimeUnit.SECONDS));
                assertEquals(version, f.plans.find(f.session).orElseThrow().version());
                assertTrue(f.call("complete_todo", "a", todo, "id", id, "result", "原工作已完成").contains("检查"));
            }
            return null;
        });
        when(f.chat.converse(eq(f.session), eq("a"), anyString(), any())).thenAnswer(inv -> {
            assertEquals("doing", f.status(id));
            assertEquals(version, f.plans.find(f.session).orElseThrow().version());
            assertTrue(f.progress.allowed(f.session));
            return new RoundtableChatService.Speech(null, 1, null, false, List.of("正在跑测试，原工作继续"));
        });
        when(f.chat.speak(anyString(), eq("b"), any())).thenAnswer(inv -> {
            f.plans.finish(f.session, "工作与独立检查均通过");
            return new RoundtableChatService.Speech(new cc.ginpika.minis.agent.chat.dto.ChatDtos.ChatReply(
                    f.session, 1L, "交付", null, true, null), 1, null, false, List.of("交付"));
        });
        var runtime = f.runtime(); var worker = f.worker(); worker.onWorkStarted(f.session);
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS));
            runtime.submitUserMessage(f.session, "@甲 现在忙什么？压力大吗？", null, null, "question");
            await(() -> mockingDetails(f.chat).getInvocations().stream().anyMatch(i -> i.getMethod().getName().equals("publish"))
                    && !runtime.snapshot(f.session).running());
            assertEquals(1, worker.workingCount(f.session));
            assertEquals("doing", f.status(id));
            verify(f.chat).converse(eq(f.session), eq("a"), contains("压力"), any());
            verify(f.chat, never()).requestStop(anyString());
            assertEquals(0, f.jdbc.queryForObject("SELECT count(*) FROM runtime_event WHERE type='INTERRUPT_REQUESTED'", Integer.class));
            assertFalse(f.plans.boundary(f.session).contains("压力"), "ordinary conversation is not a new work requirement");
        } finally { release.countDown(); }
        await(() -> "done".equals(f.status(id)) && worker.workingCount(f.session) == 0
                && "completed".equals(f.plans.find(f.session).orElseThrow().phase()) && !runtime.snapshot(f.session).running());
        assertEquals(version, f.plans.find(f.session).orElseThrow().version());
        verify(f.chat, times(2)).executeTodo(anyString(), anyString(), any(), isNull());
    }

    @Test void ordinaryConversationCanAskControllerWhilePausedWithoutResumingOrReopeningWork() throws Exception {
        var f = new Fixture(); f.plans.create(f.session, "b"); f.plans.revise(f.session, "验收", "已有工作");
        f.progress.pause(f.session, "等待用户决定");
        var before = f.plans.find(f.session).orElseThrow();
        when(f.chat.converse(eq(f.session), eq("b"), anyString(), any())).thenReturn(
                new RoundtableChatService.Speech(null, 1, null, false, List.of("目前暂停，等待决定")));
        var runtime = f.runtime(); runtime.submitUserMessage(f.session, "现在是什么情况？", null, null, "question");
        await(() -> !runtime.snapshot(f.session).running());
        verify(f.chat).converse(eq(f.session), eq("b"), anyString(), any());
        assertFalse(f.progress.allowed(f.session));
        assertEquals(before, f.plans.find(f.session).orElseThrow());
        verify(f.chat, never()).requestStop(anyString());
    }

    @Test void semanticResumeFromAnExistingConversationReopensOldWorkAndRecordsTheInstructionOnce() throws Exception {
        var f=new Fixture();String id=f.add();f.plans.create(f.session,"a");
        f.call("manage_plan","a",null,"action","revise","criteria","原验收","note","原计划");
        f.board.update(id,"dropped",null,"旧目录不可用",null,null);f.progress.pause(f.session,"迁移后等待指令");
        String input="服务器已迁移，请尝试恢复工作";
        when(f.chat.converse(eq(f.session),eq("a"),eq(input),any())).thenReturn(new RoundtableChatService.Speech(
                null,1L,null,false,List.of("已请求恢复原工作"),new RoundtableChatService.WorkControl("resume","请尝试恢复工作")));
        when(f.chat.speak(anyString(),eq("a"),any())).thenAnswer(inv->{
            assertTrue(f.progress.allowed(f.session));assertEquals("planning",f.plans.find(f.session).orElseThrow().phase());
            assertTrue(f.call("update_todo","a",null,"id",id,"status","pending","reason","迁移完成后用户要求恢复").contains("已更新"));
            assertTrue(f.call("start_work","a",null,"id",id,"reviewer","b","note","继续原目标").contains("已授权"));
            return new RoundtableChatService.Speech(new cc.ginpika.minis.agent.chat.dto.ChatDtos.ChatReply(
                    f.session,2L,"原条目已重新派工",null,true,null),2L,null,false,List.of("原条目已重新派工"));
        });
        var runtime=f.runtime();runtime.submitUserMessage(f.session,input,null,null,"resume-from-conversation");
        await(()->!f.plans.assignments(f.session).isEmpty() && !runtime.snapshot(f.session).running());
        assertEquals("pending",f.status(id));assertNull(f.board.todo(id).orElseThrow().blockReason());
        var inputs=f.messages.bySession(f.session).stream().filter(m->"user".equals(m.role()) && input.equals(m.content())).toList();
        assertEquals(1,inputs.size());assertNull(inputs.getFirst().eventKind());
        assertTrue(f.plans.boundary(f.session).contains(input));
    }

    @Test void semanticStopPausesExistingWorkInsteadOfOnlyReplying() throws Exception {
        var f=new Fixture();f.plans.create(f.session,"a");f.plans.revise(f.session,"验收","原计划");
        String input="让编码Agent停止工作吧，服务器down了";
        when(f.chat.converse(eq(f.session),eq("a"),eq(input),any())).thenReturn(new RoundtableChatService.Speech(
                null,1L,null,false,List.of("请求停止"),new RoundtableChatService.WorkControl("stop","让编码Agent停止工作吧")));
        var runtime=f.runtime();runtime.submitUserMessage(f.session,input,null,null,"stop-from-conversation");
        await(()->!f.progress.allowed(f.session) && !runtime.snapshot(f.session).running());
        verify(f.chat,atLeastOnce()).requestStop(f.session);
        verify(f.chat,never()).speak(anyString(),anyString(),any());
        assertTrue(f.plans.boundary(f.session).contains(input));
    }

    @Test void stopInvalidatesALateSemanticResumeWithoutChangingTheUserMessage() throws Exception {
        var f=new Fixture();f.plans.create(f.session,"a");f.plans.revise(f.session,"原验收","原计划");f.progress.pause(f.session,"原暂停");
        String input="请重试，我已修复系统性问题";
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(f.chat.converse(eq(f.session),eq("a"),eq(input),any())).thenAnswer(inv->{
            started.countDown();assertTrue(release.await(6,TimeUnit.SECONDS));
            return new RoundtableChatService.Speech(null,1L,null,false,List.of("恢复请求"),new RoundtableChatService.WorkControl("resume","请重试"));
        });
        var runtime=f.runtime();runtime.submitUserMessage(f.session,input,null,null,"late-resume");
        try {assertTrue(started.await(3,TimeUnit.SECONDS));runtime.cancel(f.session);}
        finally {release.countDown();}
        await(()->!runtime.snapshot(f.session).running());
        assertFalse(f.progress.allowed(f.session));
        verify(f.chat,never()).speak(anyString(),anyString(),any());
        assertEquals("conversation",f.messages.bySession(f.session).stream().filter(m->input.equals(m.content())).findFirst().orElseThrow().eventKind());
    }

    @Test void onlyTheLatestCurrentUserMessageCanBecomeAWorkInstruction() throws Exception {
        var f=new Fixture();long old=f.messages.insert(f.session,null,"user",null,null,"恢复旧工作",null,null,null,null,"conversation");
        long latest=f.messages.insert(f.session,null,"user",null,null,"先别动，我需要确认目录",null,null,null,null,"conversation");
        assertFalse(f.messages.promoteWorkInstruction(f.session,old));
        assertTrue(f.messages.promoteWorkInstruction(f.session,latest));
        assertFalse(f.messages.promoteWorkInstruction(f.session,latest));
    }

    @Test void startupRepairsPausedLegacyDispatchesWithoutAutomaticallyResumingThem() throws Exception {
        var f=new Fixture();String id=f.add();f.plans.create(f.session,"a");f.plans.revise(f.session,"原验收","原派工");
        f.plans.dispatch(f.session,id,"a","b","executing","原派工");
        f.board.update(id,"blocked",null,"旧服务器停机",null,null);f.progress.pause(f.session,"等待恢复");
        new RoundtableRecovery(f.jdbc,f.progress,f.messages).recover();
        assertEquals("planning",f.plans.find(f.session).orElseThrow().phase());
        assertTrue(f.plans.assignments(f.session).isEmpty());assertFalse(f.progress.allowed(f.session));
        assertEquals("blocked",f.status(id));
    }

    @Test void workStatusReportsScopedOperationsAndNeverPrivateReasoning() throws Exception {
        var f = new Fixture();
        try (var connection = f.jdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("coding/schema.sql"));
        }
        String id = f.add();
        var runs = new RoundtableWorkRunStore(f.jdbc); long work = runs.start(f.session, id, "a", "甲");
        f.jdbc.update("UPDATE roundtable_work_run SET reasoning='PRIVATE_REASONING_SENTINEL' WHERE id=?", work);
        f.jdbc.update("INSERT INTO coding_repository VALUES('repo','owner','project','/project',NULL,NULL,1)");
        f.jdbc.update("INSERT INTO task(kind,status,title,created_at,updated_at) VALUES('code','doing','文档',1,1)");
        Long task = f.jdbc.queryForObject("SELECT max(id) FROM task", Long.class);
        f.jdbc.update("INSERT INTO coding_run(id,task_id,owner_id,repository_id,prompt,mode,runner,max_rounds,status,round,validation_status,created_at,updated_at) "
                + "VALUES('run',?,'owner','repo','编写文档','EXECUTE','WORKSPACE',0,'RUNNING',12,'NOT_RUN',1,1)", task);
        f.jdbc.update("INSERT INTO collaboration_coding_run(run_id,session_id,todo_id,executor_id,assignment_version) VALUES('run',?,?, 'a',1)", f.session, id);
        f.jdbc.update("INSERT INTO coding_operation(run_id,attempt,round,call_id,tool,args,status,result,started_at) "
                + "VALUES('run',1,12,'call','run_validation','{}','RUNNING','build 已通过，test 仍在执行',1)");
        f.jdbc.update("INSERT INTO coding_event(run_id,type,payload,created_at) VALUES('run','reasoning.delta','PRIVATE_REASONING_SENTINEL',1)");
        var status = new CollaborationWorkStatus(f.jdbc);
        String own = status.describe(f.session, "a");
        assertTrue(own.contains("run_validation [RUNNING]"));
        assertTrue(own.contains("build 已通过，test 仍在执行"));
        assertTrue(own.contains("工具轮次：12"));
        assertFalse(own.contains("PRIVATE_REASONING_SENTINEL"));
        assertFalse(status.describe(f.sessions.insert("[]"), "a").contains("run_validation"));
        assertFalse(status.describe(f.session, "b").contains("run_validation"));
        f.plans.create(f.session, "b");
        assertTrue(status.describe(f.session, "b").contains("run_validation"), "controller sees the same session's work facts");
    }

    @Test void explicitWorkRedirectionStillStopsExecutionAndInvalidatesOldAssignment() throws Exception {
        var f = new Fixture(); String id = f.add(); f.plans.create(f.session, "b");
        f.plans.revise(f.session, "旧验收", "旧计划"); f.plans.dispatch(f.session, id, "a", "b", "executing", "旧派工");
        int version = f.plans.find(f.session).orElseThrow().version();
        var started = new CountDownLatch(1); var stop = new CountDownLatch(1);
        when(f.chat.executeTodo(anyString(), anyString(), any(), isNull())).thenAnswer(inv -> {
            started.countDown(); assertTrue(stop.await(6, TimeUnit.SECONDS));
            throw new cc.ginpika.minis.agent.chat.ChatStream.ChatCancelledException();
        });
        when(f.chat.requestStop(f.session)).thenAnswer(inv -> { stop.countDown(); return true; });
        when(f.chat.speak(anyString(), eq("b"), any())).thenReturn(new RoundtableChatService.Speech(
                new cc.ginpika.minis.agent.chat.dto.ChatDtos.ChatReply(f.session, 1L, "重新规划", null, true, null),
                1, null, false, List.of("重新规划")));
        var runtime = f.runtime(); var worker = f.worker(); worker.onWorkStarted(f.session);
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS));
            runtime.submitUserMessage(f.session, "改做新的目标", null, null, "redirect", true);
            await(() -> worker.workingCount(f.session) == 0 && !runtime.snapshot(f.session).running());
            assertEquals("pending", f.status(id));
            assertTrue(f.plans.find(f.session).orElseThrow().version() > version);
            verify(f.chat, never()).converse(anyString(), anyString(), anyString(), any());
            verify(f.chat, atLeastOnce()).requestStop(f.session);
            assertTrue(f.plans.boundary(f.session).contains("改做新的目标"));
        } finally { stop.countDown(); }
    }

    @Test void explicitStopInvalidatesQueuedConversationsWithoutReopeningPlan() throws Exception {
        var f = new Fixture(); f.plans.create(f.session, "a"); f.plans.revise(f.session, "验收", "现状");
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(f.chat.converse(anyString(), anyString(), anyString(), any())).thenAnswer(inv -> {
            started.countDown(); assertTrue(release.await(6, TimeUnit.SECONDS));
            return new RoundtableChatService.Speech(null, 1, null, false, List.of("收到"));
        });
        var runtime = f.runtime(); runtime.submitUserMessage(f.session, "第一句", null, null, "one");
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS));
            runtime.submitUserMessage(f.session, "第二句", null, null, "two");
            runtime.cancel(f.session);
        } finally { release.countDown(); }
        await(() -> !runtime.snapshot(f.session).running());
        verify(f.chat, times(1)).converse(anyString(), anyString(), anyString(), any());
        assertFalse(f.progress.allowed(f.session));
    }

    private static void await(BooleanSupplier condition) {
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> { while (!condition.getAsBoolean()) Thread.sleep(10); });
    }

    @Test void realAgendaRoutesControllerDispatchExecutionReviewAndFinalAcceptanceWithoutUserNudges() throws Exception {
        var f = new Fixture(); String id = f.add(); f.plans.create(f.session, "a");
        var topicStore = new RoundtableTopicStore(f.jdbc);
        var machine = new TopicStateMachine(topicStore, new RoundtableTopicEventStore(f.jdbc));
        var props = new cc.ginpika.minis.config.RoundtableProps();
        var picker = new SpeakerPicker(List.of(new RotationSpeakerStrategy(topicStore)), props);
        var topics = new RoundtableTopicService(f.sessions, f.messages, topicStore, f.directory, picker, machine, f.chat, props, f.board);
        var turns = new AtomicInteger();
        when(f.chat.speak(anyString(), anyString(), any())).thenAnswer(inv -> {
            assertEquals("a", inv.getArgument(1), "only the controller plans in the main session, even when user mentions someone else");
            if (turns.incrementAndGet() == 1) {
                f.call("manage_plan", "a", null, "action", "revise", "criteria", "构建与重连测试通过", "note", "明确整体目标");
                assertTrue(f.call("start_work", "a", null, "id", id, "reviewer", "b", "note", "执行首项并独立检查").contains("已授权"));
                verify(f.chat, never()).executeTodo(anyString(), anyString(), any(), any());
            } else {
                assertEquals("done", f.status(id));
                assertTrue(f.call("manage_plan", "a", null, "action", "finish", "evidence", "已核对代码差异并实际执行构建与重连测试，均通过").contains("整体交付"));
            }
            return new RoundtableChatService.Speech(new cc.ginpika.minis.agent.chat.dto.ChatDtos.ChatReply(
                    f.session, 0L, "主控决定", null, true, null), 0L, 0, false, List.of("主控决定"));
        });
        when(f.chat.executeTodo(anyString(), anyString(), any(), isNull())).thenAnswer(inv -> {
            var todo = inv.getArgument(2, RoundtableTodoStore.TodoRow.class);
            if ("checking".equals(todo.status())) f.call("review_todo", "b", todo, "id", id, "accept", true, "evidence", "读取产物并实际运行测试通过");
            else f.call("complete_todo", "a", todo, "id", id, "result", "实际产物路径与构建结果");
            return null;
        });
        var worker = f.worker();
        var runtime = new RoundtableDiscussionRuntime(topics, f.chat, new RoundtableRevision(), f.events, new RoundtableAgenda(),
                mock(RoundtableLiveBus.class), mock(SpeechPacer.class), f.sessions, f.directory, picker, machine,
                f.messages, f.board, f.progress, f.plans, f.collaboration);
        runtime.submitUserMessage(f.session, "@乙 请实现目标", null, null, "run");
        await(() -> "completed".equals(f.plans.find(f.session).orElseThrow().phase()) && worker.workingCount(f.session) == 0 && !runtime.snapshot(f.session).running());
        assertEquals(2, turns.get()); assertTrue(f.progress.allowed(f.session));
        verify(f.chat, times(2)).executeTodo(anyString(), anyString(), any(), isNull());
    }

    @Test void controllerOwnsPlanAndCannotDispatchWithoutCriteriaOrIndependentReviewer() throws Exception {
        var f = new Fixture(); f.plans.create(f.session, "a");
        assertTrue(f.call("add_todo", "b", null, "title", "越权新增", "detail", "测试").contains("只有主控"));
        String id = f.add();
        assertTrue(f.call("start_work", "a", null, "id", id, "reviewer", "b", "note", "首项").contains("本次交付物"));
        f.call("manage_plan", "a", null, "action", "revise", "criteria", "构建和重连测试通过", "note", "按目标拆分");
        assertTrue(f.call("start_work", "a", null, "id", id, "reviewer", "a", "note", "自检").contains("另一位"));
        assertTrue(f.call("start_work", "a", null, "id", id, "reviewer", "b", "note", "先验证边界").contains("已授权"));
        var current = f.board.claimAssigned(f.session, "a", id, false).orElseThrow();
        assertTrue(f.call("add_todo", "a", current, "title", "执行时扩张", "detail", "test").contains("只有主控"));
        f.call("update_todo", "b", null, "id", id, "status", "dropped");
        assertEquals("doing", f.status(id), "non-controller cannot cancel an active assignment");
        assertEquals(1, f.board.list(f.session).size());
    }

    @Test void scopeAndGranularityRefusalCannotCreateRewriteOrDispatchWork() throws Exception {
        var f=new Fixture();f.plans.create(f.session,"a");
        assertTrue(f.call("add_todo","a",null,"title","实现完整游戏","detail","含全部业务").contains("本次交付物"));
        f.call("manage_plan","a",null,"action","revise","criteria","单一实验通过","note","只验证关键假设");
        when(f.scopeGuard.check(eq(f.session),eq("具体 TODO"),anyString())).thenReturn("范围或粒度复核未通过：缩小为一个可验收假设");
        assertTrue(f.call("add_todo","a",null,"title","实现完整游戏","detail","含全部业务").contains("复核未通过"));
        assertTrue(f.board.list(f.session).isEmpty());
        String id=f.add();
        assertTrue(f.call("update_todo","a",null,"id",id,"title","改成完整游戏").contains("复核未通过"));
        assertEquals("产出可验证结果",f.board.todo(id).orElseThrow().title());
        assertTrue(f.call("start_work","a",null,"id",id,"reviewer","b","note","尝试绕过新增检查").contains("复核未通过"));
        assertEquals("planning",f.plans.find(f.session).orElseThrow().phase());
        assertEquals("pending",f.status(id));
    }
    @Test void invalidContractsAndWorkerContractEditsCannotMutateTheBoard() throws Exception {
        var f=new Fixture();f.plans.create(f.session,"a");
        f.call("manage_plan","a",null,"action","revise","criteria","决策证据充分","note","按最大不确定性拆解");
        assertTrue(f.call("add_todo","a",null,"title","实验","contract",java.util.Map.of("outcome","不完整契约")).contains("本次未修改"));
        assertTrue(f.board.list(f.session).isEmpty());
        var contract=java.util.Map.of("outcome","单一实测结果","acceptanceCriteria",List.of(java.util.Map.of("id","C1","text","证据可复现")),"stopCondition","足以支持决策");
        f.call("add_todo","a",null,"title","实验","detail","只验证风险","owner","a","contract",contract);
        String id=f.board.list(f.session).getFirst().id();
        f.plans.dispatch(f.session,id,"a","b","executing","先取得决策证据");
        var current=f.board.claimAssigned(f.session,"a",id,false).orElseThrow();
        assertTrue(f.call("update_todo","a",current,"id",id,"status","blocked","reason","想改验收","contract",contract).contains("执行者只能"));
        assertEquals("doing",f.status(id));
    }
    @Test void reviewStateAndEvidenceCommitTogetherOrRollBackTogether() throws Exception {
        var f=new Fixture();f.plans.create(f.session,"a");String id=f.add();
        f.plans.revise(f.session,"可验证结果","检查实际证据",new CollaborationPlan.Scope("结果","其他","已核对",""));
        f.plans.dispatch(f.session,id,"a","b","executing","首项");var author=f.board.claimAssigned(f.session,"a",id,false).orElseThrow();
        f.call("complete_todo","a",author,"id",id,"result","实际产出");
        f.plans.checking(f.session,id,f.plans.find(f.session).orElseThrow().version());var reviewer=f.board.claimAssigned(f.session,"b",id,true).orElseThrow();
        doThrow(new IllegalStateException("模拟证据写入失败")).when(f.quality).review(eq(id),any(),eq(false),anyInt(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->f.call("review_todo","b",reviewer,"id",id,"accept",false,"evidence","存在具体问题"));
        assertEquals("checking",f.status(id),"不能留下已返工但证据未写入的一半状态");
        assertEquals(0,f.jdbc.queryForObject("SELECT count(*) FROM runtime_event WHERE type='TASK_REVISED'",Integer.class));
    }

    @Test void managedWorkerReturnsRejectionToControllerAndDoesNotRunDownstreamOrReworkItself() throws Exception {
        var f = new Fixture(); String id = f.add(); f.plans.create(f.session, "a");
        String downstream = f.board.add(f.session, null, "b", "依赖后续", "test", "a", "[\"" + id + "\"]");
        f.plans.revise(f.session, "测试通过", "先处理首项",new CollaborationPlan.Scope("结果","其他","已实查",""));
        f.plans.dispatch(f.session, id, "a", "b", "executing", "首次执行");
        var executions = new AtomicInteger(); var reviews = new AtomicInteger();
        when(f.chat.executeTodo(anyString(), anyString(), any(), isNull())).thenAnswer(inv -> {
            var todo = inv.getArgument(2, RoundtableTodoStore.TodoRow.class);
            assertEquals(id, todo.id());
            if ("checking".equals(todo.status())) {
                boolean accept = reviews.incrementAndGet() > 1;
                f.call("review_todo", "b", todo, "id", id, "accept", accept, "evidence", accept ? "实际测试通过" : "缺少重连测试");
            } else {
                if (executions.incrementAndGet() > 1) assertTrue(todo.blockReason().contains("重连"));
                f.call("complete_todo", "a", todo, "id", id, "result", "产出路径与检查结果");
            }
            return null;
        });
        var worker = f.worker(); worker.onWorkStarted(f.session);
        await(() -> reviews.get() == 1 && worker.workingCount(f.session) == 0 && "planning".equals(f.plans.find(f.session).orElseThrow().phase())
                && f.jdbc.queryForObject("SELECT count(*) FROM runtime_event WHERE type='AGENT_REPORT'",Integer.class)==1);
        worker.onWorkStarted(f.session);
        assertEquals(1, executions.get()); assertEquals("pending", f.status(id)); assertEquals("pending", f.status(downstream));
        assertEquals(1, f.jdbc.queryForObject("SELECT count(*) FROM runtime_event WHERE type='AGENT_REPORT'", Integer.class));
        f.call("start_work", "a", null, "id", id, "reviewer", "b", "note", "根据检查意见在原条目返工");
        worker.onWorkStarted(f.session);
        await(() -> "done".equals(f.status(id)) && worker.workingCount(f.session) == 0 && "planning".equals(f.plans.find(f.session).orElseThrow().phase()));
        assertEquals(2, executions.get()); assertEquals(2, reviews.get());
        assertEquals("pending", f.status(downstream), "unlocked dependencies still require a controller dispatch");
        assertTrue(f.call("manage_plan", "a", null, "action", "finish", "evidence", "局部测试通过").contains("未完成"));
        f.call("update_todo", "a", null, "id", downstream, "status", "dropped", "reason", "整体目标已在首项覆盖");
        assertTrue(f.call("manage_plan", "a", null, "action", "finish", "evidence", "代码差异、构建和重连测试均通过").contains("整体交付"));
        assertEquals("completed", f.plans.find(f.session).orElseThrow().phase());
    }

    @Test void managedBlockersDoNotCreateIssuesAndIssueConclusionsDoNotRequeueWork() throws Exception {
        var f = new Fixture(); String id = f.add(); f.plans.create(f.session, "a");
        var service = mock(CollaborationService.class);
        new CollaborationCoordinator(service, f.collaboration, f.board, f.discussions, f.directory, f.events, f.plans);
        f.board.update(id, "blocked", null, "共同环境故障", null, null);
        for (int n = 0; n < 5; n++) f.events.publish(RuntimeEvent.of(RuntimeEventType.TASK_BLOCKED, f.session, "b", "a", id, null, java.util.Map.of("reason", "共同环境故障")));
        verifyNoInteractions(service);
        var issue = new CollaborationStore.Issue("issue", f.session, f.sessions.insert("[]"), "环境决定", "open", null, 1, 1, null);
        f.collaboration.insert(issue); f.collaboration.linkWork(issue.id(), id);
        new CollaborationIssueActions(f.collaboration, f.board, f.messages, f.sessions, f.progress, f.events, f.plans).resolve(issue, "统一改用可用环境");
        assertEquals("blocked", f.status(id));
        assertTrue(f.board.todo(id).orElseThrow().detail().contains("待主控决定"));
    }

    @Test void migratedWorkspaceCanReopenBlockedAndDroppedWorkWithoutReplayingOldAssignments() throws Exception {
        var f=new Fixture();String blocked=f.add();
        String dropped=f.board.add(f.session,null,"a","迁移后恢复的工作","保留原契约","a",null);
        f.plans.create(f.session,"a");
        f.call("manage_plan","a",null,"action","revise","criteria","新目录验证通过","note","迁移后核对");
        f.board.update(blocked,"blocked",null,"旧空间不可用",null,null);
        f.board.setBlockWith(blocked,"user");
        f.board.update(dropped,"dropped",null,"因旧空间停用而放弃",null,null);
        f.plans.dispatch(f.session,blocked,"a","b","executing","旧派工");
        var bindings=mock(CollaborationWorkspaceBinding.class);
        when(bindings.bind(eq("project"),any())).thenReturn(new CollaborationWorkspaceBinding.Location(
                "project","new-repo","new-workspace","新空间","app","/new/app",true));
        when(f.discussions.snapshot(f.session)).thenReturn(new RoundtableDiscussionRuntime.ConversationSnapshot(
                f.session,"multi-agent",false,null,null,null,java.util.Map.of(),java.util.Map.of()));
        var projects=new cc.ginpika.minis.project.ProjectStore(f.jdbc);
        projects.insert("project",null,"项目","software",false,"active",null);
        f.jdbc.update("UPDATE hub_session SET project_id='project' WHERE id=?",f.session);
        var service=new CollaborationService(f.collaboration,f.chat,f.sessions,f.messages,f.directory,projects,
                f.discussions,mock(RoundtableWorkRuntime.class),mock(CollaborationIssueActions.class),f.plans,bindings);
        service.bindWorkspace("project",new cc.ginpika.minis.coding.api.CodingDtos.OpenProject("new-workspace","app",null,false,null));
        assertEquals("planning",f.plans.find(f.session).orElseThrow().phase());
        assertTrue(f.plans.assignments(f.session).isEmpty());
        assertEquals("blocked",f.status(blocked));assertEquals("dropped",f.status(dropped));
        assertTrue(f.plans.workEvidence(f.session).contains(dropped),"放弃条目的 ID 必须在重规划上下文中可见");
        for(String id:List.of(blocked,dropped)) {
            assertTrue(f.call("update_todo","a",null,"id",id,"status","pending").contains("reason"));
            assertTrue(f.call("update_todo","b",null,"id",id,"status","pending","reason","新空间就绪").contains("主控"));
            assertTrue(f.call("update_todo","a",null,"id",id,"status","pending","reason","新空间就绪").contains("已更新"));
            assertEquals("pending",f.status(id));
            assertNull(f.board.todo(id).orElseThrow().blockReason());
            assertNull(f.board.todo(id).orElseThrow().blockWith());
        }
        assertTrue(f.call("start_work","a",null,"id",blocked,"reviewer","b","note","在新空间重新验证").contains("已授权"));
        assertTrue(f.plans.assignment(f.session,blocked).isPresent());
    }

    @Test void planRejectsInvalidDependenciesDuplicateWorkAndStaleAssignmentTools() throws Exception {
        var f = new Fixture(); String id = f.add(); f.plans.create(f.session, "a");
        f.plans.revise(f.session,"可验证","现状",new CollaborationPlan.Scope("结果","其他","已实查",""));
        assertTrue(f.call("add_todo", "a", null, "title", "产出可验证结果", "detail", "test").contains("同名"));
        assertTrue(f.call("add_todo", "a", null, "title", "新工作", "detail", "test", "depends_on", List.of("missing")).contains("无效"));
        String other = f.board.add(f.session, null, "a", "依赖", "test", "a", "[\"" + id + "\"]");
        assertTrue(f.call("update_todo", "a", null, "id", id, "depends_on", List.of(other)).contains("循环"));
        assertNull(f.board.todo(id).orElseThrow().dependsOn());
        f.plans.revise(f.session, "可验证", "初始规划",new CollaborationPlan.Scope("结果","其他","已实查",""));
        f.plans.dispatch(f.session, id, "a", "b", "executing", "执行");
        var old = f.board.claimAssigned(f.session, "a", id, false).orElseThrow();
        int version = f.plans.find(f.session).orElseThrow().version();
        f.plans.reopen(f.session);
        assertTrue(f.plans.find(f.session).orElseThrow().version() > version);
        assertTrue(f.call("complete_todo", "a", old, "id", id, "result", "迟到成果").contains("失效"));
        assertEquals("doing", f.status(id));
        f.plans.dispatch(f.session, id, "a", "b", "executing", "新的派工版本");
        assertFalse(f.plans.checking(f.session, id, version));
        assertFalse(f.plans.returned(f.session, id, "旧收尾", version));
        assertEquals("executing", f.plans.find(f.session).orElseThrow().phase());
        new RoundtableRecovery(f.jdbc, f.progress, f.messages).recover();
        assertEquals("blocked", f.status(id)); assertFalse(f.progress.allowed(f.session));
    }

    @Test void concurrentPublicClaimHasExactlyOneWinner() throws Exception {
        var f = new Fixture();
        String id = f.board.add(f.session, null, null, "公共条目", null, "a", null);
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var claims = List.of("a", "b").stream().map(peer -> executor.submit(() -> {
                ready.countDown(); start.await(); return f.board.claim(f.session, peer).isPresent();
            })).toList();
            assertTrue(ready.await(2, TimeUnit.SECONDS)); start.countDown();
            int winners = 0; for (var future : claims) if (future.get(3, TimeUnit.SECONDS)) winners++;
            assertEquals(1, winners);
            assertEquals("doing", f.status(id));
        }
    }

    @Test void submissionDoesNotUnlockDownstreamAndAuthorCannotReview() throws Exception {
        var f = new Fixture(); String id = f.add();
        f.board.add(f.session, null, "b", "依赖成果", null, "a", "[\"" + id + "\"]");
        var todo = f.board.claim(f.session, "a").orElseThrow();
        assertTrue(f.call("complete_todo", "a", todo, "id", id, "result", "文件 A；检查 B 通过").contains("提交检查"));
        assertEquals("reviewing", f.status(id));
        assertTrue(f.board.claimable(f.session, "b").isEmpty());
        assertTrue(f.board.claimReview(f.session, "a").isEmpty());
        var review = f.board.claimReview(f.session, "b").orElseThrow();
        assertTrue(f.call("review_todo", "a", review, "id", id, "accept", true, "evidence", "checked").contains("他人成果"));
        f.call("review_todo", "b", review, "id", id, "accept", true, "evidence", "实际读取 A 并检查 B 通过");
        assertEquals("done", f.status(id));
        assertEquals(1, f.board.claimable(f.session, "b").size());
    }

    @Test void actualWorkerAutomaticallyReworksRejectedResultThenReviewsAgain() throws Exception {
        var f = new Fixture(); String id = f.add(); var reviews = new AtomicInteger(); var executions = new AtomicInteger();
        when(f.chat.executeTodo(anyString(), anyString(), any(), isNull())).thenAnswer(inv -> {
            var todo = (RoundtableTodoStore.TodoRow) inv.getArgument(2);
            if ("checking".equals(todo.status())) {
                boolean accept = reviews.incrementAndGet() == 2;
                f.call("review_todo", inv.getArgument(1), todo, "id", todo.id(), "accept", accept,
                        "evidence", accept ? "测试已通过" : "缺少断线重连测试，请补充");
            } else {
                if (executions.incrementAndGet() == 2) assertTrue(todo.blockReason().contains("断线重连"));
                f.call("complete_todo", inv.getArgument(1), todo, "id", todo.id(), "result", "已产出并验证");
            }
            return null;
        });
        var worker = f.worker(); worker.onWorkStarted(f.session);
        await(() -> "done".equals(f.status(id)) && worker.workingCount(f.session) == 0);
        assertEquals(2, executions.get()); assertEquals(2, reviews.get());
        assertTrue(f.board.todo(id).orElseThrow().result().contains("检查证据"));
    }

    @Test void plainModelReturnNeverMarksTodoDone() throws Exception {
        var f = new Fixture(); String id = f.add(); var worker = f.worker();
        worker.onWorkStarted(f.session);
        await(() -> "blocked".equals(f.status(id)) && worker.workingCount(f.session) == 0);
        assertTrue(f.board.todo(id).orElseThrow().blockReason().contains("未提交成果"));
        assertEquals(0, f.jdbc.queryForObject("SELECT count(*) FROM runtime_event WHERE type='TASK_COMPLETED'", Integer.class));
    }

    @Test void cancelledExecutionReturnsToPendingAndCannotResumeWhilePaused() throws Exception {
        var f = new Fixture(); String id = f.add();
        when(f.chat.executeTodo(anyString(), anyString(), any(), isNull())).thenAnswer(inv -> {
            f.progress.pause(f.session, "用户停止");
            throw new cc.ginpika.minis.agent.chat.ChatStream.ChatCancelledException();
        });
        var worker = f.worker(); worker.onWorkStarted(f.session);
        await(() -> worker.workingCount(f.session) == 0);
        assertEquals("pending", f.status(id));
        f.events.publish(RuntimeEvent.of(RuntimeEventType.WORK_STARTED, f.session, "runtime", java.util.Map.of()));
        verify(f.chat, times(1)).executeTodo(anyString(), anyString(), any(), isNull());
    }

    @Test void toolsCannotMutateAnotherSessionOrBypassReview() throws Exception {
        var f = new Fixture(); String id = f.add();
        String foreign = f.board.add(f.sessions.insert("[]"), null, "a", "另一会话", null, "a", null);
        assertTrue(f.call("complete_todo", "a", null, "id", foreign, "result", "claimed").contains("没有这条"));
        assertTrue(f.call("update_todo", "a", null, "id", foreign, "status", "dropped").contains("没有这条"));
        assertEquals("pending", f.status(foreign));
        assertTrue(f.call("update_todo", "a", null, "id", id, "status", "done").contains("只能"));
        assertEquals("pending", f.status(id));
    }

    @Test void emptyEvidenceCannotCompleteAReview() throws Exception {
        var f = new Fixture(); String id = f.add(); var todo = f.board.claim(f.session, "a").orElseThrow();
        assertTrue(f.call("complete_todo", "a", todo, "id", id).contains("不能为空"));
        f.call("complete_todo", "a", todo, "id", id, "result", "产出");
        var review = f.board.claimReview(f.session, "b").orElseThrow();
        f.call("review_todo", "b", review, "id", id, "accept", true, "evidence", " ");
        assertEquals("checking", f.status(id));
    }

    @Test void stepAndAttemptCountsPersistWithoutPausingAndExplicitPauseStillWorks() throws Exception {
        var f = new Fixture(); String id = f.add();
        for (int n = 0; n < 100; n++) assertTrue(f.progress.step(f.session));
        assertTrue(new RoundtableProgress(f.jdbc).step(f.session));
        assertTrue(f.progress.allowed(f.session));
        f.progress.pause(f.session,"用户暂停"); assertFalse(f.progress.step(f.session));
        f.progress.resume(f.session); assertTrue(f.progress.step(f.session));
        for (int n = 0; n < 10; n++) assertTrue(f.progress.attempt(id));
        assertTrue(new RoundtableProgress(f.jdbc).attempt(id));
        f.progress.retry(id); assertTrue(f.progress.attempt(id));
    }

    @Test void userTargetFromToolReachesActualDiscussionSubscriberAndPausesScheduling() throws Exception {
        var f = new Fixture(); String id = f.add(); var todo = f.board.claim(f.session, "a").orElseThrow();
        var topics = mock(RoundtableTopicService.class); var topic = mock(AgentDtos.TopicView.class);
        when(topic.id()).thenReturn("topic"); when(topics.currentTopic(f.session)).thenReturn(Optional.of(topic));
        var machine = mock(TopicStateMachine.class); var agenda = mock(RoundtableAgenda.class);
        new RoundtableDiscussionRuntime(topics, f.chat, mock(RoundtableRevision.class), f.events, agenda,
                mock(RoundtableLiveBus.class), mock(SpeechPacer.class), f.sessions, f.directory,
                mock(SpeakerPicker.class), machine, f.messages, f.board, f.progress, f.plans, f.collaboration);
        f.call("request_discussion", "a", todo, "reason", "需要用户确定边界", "with", "用户");
        assertFalse(f.progress.allowed(f.session));
        verify(machine).askUser("topic", "a", "讨论请求");
        verifyNoInteractions(agenda);
        assertEquals("blocked", f.status(id));
    }

    @Test void issueConclusionRequeuesOnlyItsLinkedTodoAndDoesNotUnpauseStoppedParent() throws Exception {
        var f = new Fixture(); String id = f.add();
        f.board.update(id, "blocked", null, "need choice", null, null);
        String other = f.add(); f.board.update(other, "blocked", null, "other", null, null);
        var issue = new CollaborationStore.Issue("issue", f.session, f.sessions.insert("[]"), "决定方案", "open", null, 1, 1, null);
        f.collaboration.insert(issue); f.collaboration.linkWork(issue.id(), id);
        f.progress.pause(f.session, "用户停止");
        var actions = new CollaborationIssueActions(f.collaboration, f.board, f.messages, f.sessions, f.progress, f.events, f.plans);
        assertTrue(actions.resolve(issue, "采用方案 A，补充测试"));
        assertFalse(actions.resolve(issue, "重复结论"));
        assertEquals("pending", f.status(id)); assertEquals("blocked", f.status(other));
        assertTrue(f.board.todo(id).orElseThrow().detail().contains("方案 A"));
        assertFalse(f.progress.allowed(f.session));
        assertEquals(1, f.messages.bySession(f.session).stream().filter(m -> "issue_resolved".equals(m.eventKind())).count());
    }

    @Test void restartPausesAndExposesUnfinishedWorkWithoutReplayingIt() throws Exception {
        var f = new Fixture(); String id = f.add(); f.board.claim(f.session, "a");
        var runs = new RoundtableWorkRunStore(f.jdbc); runs.start(f.session, id, "a", "甲");
        var recovery = new RoundtableRecovery(f.jdbc, f.progress, f.messages); recovery.recover(); recovery.recover();
        assertEquals("blocked", f.status(id)); assertFalse(f.progress.allowed(f.session));
        assertEquals("blocked", runs.latest(id).orElseThrow().status());
        assertEquals(1, f.messages.bySession(f.session).size());
        verifyNoInteractions(f.chat);
    }

    @Test void blockedToolCreatesOneIsolatedIssueAndConclusionResumesItsOwner() throws Exception {
        var f = new Fixture(); String id = f.add();
        var collaboration = mock(CollaborationService.class);
        when(collaboration.createIssue(eq(f.session), anyString(), anyList())).thenAnswer(inv -> {
            var issue = new CollaborationStore.Issue("issue", f.session, f.sessions.insert("[\"a\",\"b\"]"),
                    inv.getArgument(1), "open", null, 1, 1, null);
            f.collaboration.insert(issue); return issue;
        });
        new CollaborationCoordinator(collaboration, f.collaboration, f.board, f.discussions, f.directory, f.events, f.plans);
        var todo = f.board.claim(f.session, "a").orElseThrow();
        f.call("request_discussion", "a", todo, "reason", "两种方案有分歧", "with", "乙");
        var issue = f.collaboration.issues().getFirst();
        assertEquals(id, f.collaboration.workOf(issue.id()).orElseThrow());
        assertNotEquals(f.session, issue.sessionId());
        verify(f.discussions).submitUserMessage(eq(issue.sessionId()), contains("resolve_issue"), isNull(), isNull(), anyString());
        f.events.publish(RuntimeEvent.of(RuntimeEventType.TASK_BLOCKED, f.session, "a", "b", id, null, java.util.Map.of("reason", "重复")));
        verify(collaboration, times(1)).createIssue(anyString(), anyString(), anyList());
        new CollaborationIssueActions(f.collaboration, f.board, f.messages, f.sessions, f.progress, f.events, f.plans)
                .resolve(issue, "采用 A 并补充测试");
        assertEquals("pending", f.status(id));
        assertTrue(f.progress.allowed(f.session));
    }

    @Test void goalAndConclusionsSurviveLongHistoryAndReviewResultsEnterDiscussion() throws Exception {
        var f = new Fixture(); String id = f.add();
        f.messages.insertNotice(f.session, null, "目标：保持断线重连正确", "task_context");
        for (int n = 0; n < 140; n++) f.messages.insert(f.session, null, "user", null, null, "消息 " + n, null, null, null, null);
        f.messages.insertNotice(f.session, null, "议题决定使用 A", "issue_resolved");
        f.board.update(id, "done", "成果路径 /result.md；测试通过", null, null, null);
        var context = new RoundtableContextBuilder(f.directory, mock(cc.ginpika.minis.agent.member.AgentPromptService.class),
                mock(TopicStateMachine.class), f.messages, f.board, new cc.ginpika.minis.config.RoundtableProps(),new cc.ginpika.minis.project.ProjectKnowledgeStore(f.jdbc));
        assertTrue(context.pinnedContext(f.session).contains("保持断线重连"));
        assertTrue(context.pinnedContext(f.session).contains("使用 A"));
        assertTrue(context.pinnedContext(f.session).contains("消息 139"));
        // boardBlock is also used by actual dispatch; no unverified worker prose is treated as an accepted result.
        var method = RoundtableContextBuilder.class.getDeclaredMethod("boardBlock", String.class);
        method.setAccessible(true);
        assertTrue(((String) method.invoke(context, f.session)).contains("/result.md"));
    }

    @Test void committedControlEventsPreservePayloadAndDoNotCoalesceDifferentTodos() throws Exception {
        var f = new Fixture(); var received = new java.util.ArrayList<RuntimeEvent>();
        f.events.subscribeAll(row -> {
            received.add(RuntimeEventBus.event(row));
            f.messages.insertNotice(f.session, null, "监听者写入", "audit");
            return true;
        });
        var transaction = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(f.jdbc.getDataSource()));
        transaction.executeWithoutResult(status -> {
            f.events.publish(RuntimeEvent.of(RuntimeEventType.TASK_COMPLETED, f.session, "a", java.util.Map.of("todoId", "one")));
            f.events.publish(RuntimeEvent.of(RuntimeEventType.TASK_COMPLETED, f.session, "a", java.util.Map.of("todoId", "two")));
            assertTrue(received.isEmpty(), "listeners must not read uncommitted state");
        });
        assertEquals(List.of("one", "two"), received.stream().map(e -> e.text("todoId", "")).toList());
        transaction.executeWithoutResult(status -> {
            f.events.publish(RuntimeEvent.of(RuntimeEventType.WORK_STARTED, f.session, "a", java.util.Map.of()));
            status.setRollbackOnly();
        });
        assertEquals(2, received.size());
        assertEquals(2, f.messages.bySession(f.session).size(), "listener writes must commit independently");
    }

    @Test void waitingTopicCannotAutoResumeLegacyPendingWork() throws Exception {
        var f = new Fixture(); f.add();
        var topics = mock(RoundtableTopicService.class); var topic = mock(AgentDtos.TopicView.class);
        when(topic.id()).thenReturn("topic"); when(topic.status()).thenReturn("waiting");
        when(topics.currentTopic(f.session)).thenReturn(Optional.of(topic));
        var runtime = new RoundtableDiscussionRuntime(topics, f.chat, mock(RoundtableRevision.class), f.events,
                mock(RoundtableAgenda.class), mock(RoundtableLiveBus.class), mock(SpeechPacer.class), f.sessions,
                f.directory, mock(SpeakerPicker.class), mock(TopicStateMachine.class), f.messages, f.board, f.progress, f.plans, f.collaboration);
        var resume = RoundtableDiscussionRuntime.class.getDeclaredMethod("autoResume", String.class);
        resume.setAccessible(true); resume.invoke(runtime, f.session);
        assertEquals(0, f.jdbc.queryForObject("SELECT count(*) FROM runtime_event WHERE type='WORK_STARTED'", Integer.class));
    }

    @Test void completedDiscussionClearsItsQueueAndAllowsTheNextAutonomousReport() throws Exception {
        var f = new Fixture(); var topics = mock(RoundtableTopicService.class);
        var topic = mock(AgentDtos.TopicView.class); when(topic.id()).thenReturn("topic");
        when(topic.status()).thenReturn("active"); when(topics.currentTopic(f.session)).thenReturn(Optional.of(topic));
        var advance = mock(RoundtableTopicService.Advance.class); when(advance.topicId()).thenReturn("topic");
        when(topics.open(anyString(), anyList(), anyString(), any(), any(), anyBoolean(), nullable(String.class))).thenReturn(advance);
        when(topics.openController(anyString(), anyList(), any(), anyString(), any(), anyBoolean())).thenReturn(advance);
        when(topics.openDirected(anyString(), anyList(), anyList(), any())).thenReturn(advance);
        var agenda = mock(RoundtableAgenda.class);
        doAnswer(inv -> { inv.getArgument(1, Runnable.class).run(); return null; }).when(agenda).submit(anyString(), any());
        var runtime = new RoundtableDiscussionRuntime(topics, f.chat, new RoundtableRevision(), f.events, agenda,
                mock(RoundtableLiveBus.class), mock(SpeechPacer.class), f.sessions, f.directory,
                mock(SpeakerPicker.class), mock(TopicStateMachine.class), f.messages, f.board, f.progress, f.plans, f.collaboration);
        runtime.submitUserMessage(f.session, "开始推进", null, null, "run");
        assertFalse(runtime.snapshot(f.session).running(), "a completed turn must not leave an orphan queue");
        assertFalse(f.progress.allowed(f.session), "an idle controller must expose that it made no actionable decision");
        f.progress.resume(f.session);
        runtime.report(f.session, "a", "有新证据", null);
        verify(topics).openDirected(eq(f.session), anyList(), argThat(list -> list.size() == 1 && list.getFirst().id().equals("a")), any());
        assertFalse(runtime.snapshot(f.session).running());
    }
}
