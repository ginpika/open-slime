package cc.ginpika.minis.coding;

import cc.ginpika.minis.agent.member.AgentDirectory;
import cc.ginpika.minis.agent.roundtable.*;
import cc.ginpika.minis.agent.runtime.event.*;
import cc.ginpika.minis.ai.core.*;
import cc.ginpika.minis.ai.llm.OpenAiChatClient;
import cc.ginpika.minis.ai.runtime.ModelLoop;
import cc.ginpika.minis.coding.api.CodingDtos.*;
import cc.ginpika.minis.coding.config.CodingProps;
import cc.ginpika.minis.coding.context.CodingContext;
import cc.ginpika.minis.coding.engine.CodingTools;
import cc.ginpika.minis.coding.integration.CodingAgent;
import cc.ginpika.minis.coding.repository.CodingWorkspace;
import cc.ginpika.minis.coding.runner.CodingProcesses;
import cc.ginpika.minis.coding.store.CodingStore;
import cc.ginpika.minis.coding.workspace.WorkspaceProjects;
import cc.ginpika.minis.config.*;
import cc.ginpika.minis.project.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real SQLite, snapshots, patching, npm build/test and application; only model output and remote transport are replaced. */
class CollaborationCodingLoopTest {
    @TempDir Path temp;
    final ObjectMapper json=new ObjectMapper();
    CodingFacade coding;CodingWorkspace files;CodingStore store;ProjectWorkExecution authority;
    CollaborationPlan plans;RoundtableTodoStore board;RoundtableTaskTools taskTools;
    String session,todo,runId;Path project;JdbcTemplate jdbc;
    final AtomicInteger commandExecutions=new AtomicInteger();

    @BeforeEach void setup() throws Exception {
        var source=new DriverManagerDataSource("jdbc:sqlite:"+temp.resolve("fixture.db"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql"),new ClassPathResource("coding/schema.sql")).execute(source);
        jdbc=new JdbcTemplate(source);jdbc.execute("PRAGMA journal_mode=WAL");
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        var props=new CodingProps();props.setDataDir(temp.resolve("managed").toString());
        files=new CodingWorkspace(props,json);project=Files.createDirectory(temp.resolve("project"));
        Files.writeString(project.resolve("README.md"),"fixture\n");
        files.git(project,"init","--quiet","--separate-git-dir",temp.resolve("project-metadata").toString());
        files.git(project,"add","--all");files.git(project,"-c","user.name=Test","-c","user.email=test@localhost","commit","--quiet","--no-verify","-m","initial");
        store=new CodingStore(jdbc,tx,json);var repository=store.register("alice","fixture",project.toString(),null,null);
        new ProjectStore(jdbc).insert("project",null,"Game","software",false,"active","逐步验证游戏核心");
        var sessions=new RoundtableSessionStore(jdbc);session=sessions.insert("[\"author\",\"reviewer\"]");sessions.touch(session,"验证一个纯逻辑函数");
        jdbc.update("UPDATE hub_session SET project_id='project' WHERE id=?",session);
        var bindings=new ProjectWorkspaceStore(jdbc);bindings.bind("project",repository.id());authority=new ProjectWorkExecution(jdbc);
        plans=new CollaborationPlan(jdbc, new cc.ginpika.minis.project.ProjectKnowledgeStore(jdbc));plans.create(session,"reviewer");
        plans.revise(session,"函数构建和测试通过","先验证一个最小假设",new CollaborationPlan.Scope("一个可验证函数","完整游戏","空工程已核实","后续再决定"));
        board=new RoundtableTodoStore(jdbc);todo=board.add(session,null,"author","验证核心函数","单一产出：加法函数，npm 构建与测试，当前无业务代码","reviewer",null);
        plans.dispatch(session,todo,"author","reviewer","executing","最小实验");board.claimAssigned(session,"author",todo,false).orElseThrow();

        var workspaces=mock(WorkspaceProjects.class);
        when(workspaces.binding(repository.id())).thenReturn(new WorkspaceProjects.Binding(repository.id(),"workspace","project",project.toString(),"fingerprint"));
        when(workspaces.project("alice",repository.id())).thenReturn(new CodingProject(repository.id(),"fixture","workspace","fixture","ssh","project",null,true,0));
        when(workspaces.bound(any())).thenReturn(true);
        doAnswer(inv->{Run run=inv.getArgument(0);files.prepare(run.id(),repository);return null;}).when(workspaces).prepare(any());
        when(workspaces.accept(any(),anyString())).thenAnswer(inv->{Run run=inv.getArgument(0);return files.accept(run.id(),repository,inv.getArgument(1));});
        when(workspaces.integrate(any())).thenAnswer(inv->{Run run=inv.getArgument(0);return files.integrate(run.id(),repository);});
        var processes=mock(CodingProcesses.class);var outputs=new ConcurrentHashMap<String,Map<String,Object>>();
        when(processes.start(any(),anyString(),anyInt())).thenAnswer(inv->{
            Run run=inv.getArgument(0);String command=inv.getArgument(1);commandExecutions.incrementAndGet();
            var args=System.getProperty("os.name").startsWith("Windows")?List.of("cmd.exe","/d","/c",command):List.of("sh","-lc",command);
            Process process=new ProcessBuilder(args).directory(files.checkout(run.id()).toFile()).redirectErrorStream(true).start();
            if(!process.waitFor(30,TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalStateException("fixture command timed out");}
            String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            String id=UUID.randomUUID().toString();Map<String,Object> result=Map.of("processId",id,"running",false,"exitCode",process.exitValue(),"output",output);
            outputs.put(id,result);return result;
        });
        when(processes.await(any(),anyString(),any())).thenAnswer(inv->outputs.get(inv.getArgument(1)));
        var settings=mock(SettingsService.class);when(settings.resolveChatModel(any())).thenReturn(new ModelTarget("fixture","key","model"));
        when(settings.chatContextWindow(any())).thenReturn(262144);
        var agent=mock(CodingAgent.class);when(agent.modelId(any())).thenReturn("model");when(agent.instructions()).thenReturn("fixture");
        when(agent.profile()).thenReturn(new Agent(BuiltinAgents.CODING,"编码 Agent","model"));
        var client=mock(OpenAiChatClient.class);
        var patches=json.createArrayNode();
        Map<String,String> content=new LinkedHashMap<>();
        content.put("package.json","{\"private\":true,\"type\":\"module\",\"scripts\":{\"build\":\"node build.mjs\",\"test\":\"node --test\"}}");
        content.put("core.js","export const add=(a,b)=>a+b;\n");
        content.put("core.test.js","import test from 'node:test';import assert from 'node:assert/strict';import {add} from './core.js';test('add',()=>{assert.equal(add(1,2),3);assert.equal(add(-1,1),0)});\n");
        content.put("build.mjs","import {mkdirSync,copyFileSync} from 'node:fs';mkdirSync('dist',{recursive:true});copyFileSync('core.js','dist/core.js');console.log('build passed');\n");
        content.forEach((name,text)->patches.addObject().put("path",name).put("expectedHash","MISSING").put("newText",text));
        when(client.chatRaw(any(),any(),any(),anyString(),any())).thenReturn(
                response(call("apply_patch",json.createObjectNode().set("patches",patches))),
                response(call("run_validation",json.createObjectNode().put("command","npm run build && npm test"))),
                new OpenAiChatClient.RawResult(json.createObjectNode().put("role","assistant").put("content","已构建并测试当前函数"),"raw","stop"));
        var tools=new CodingTools(json,files,processes,store);
        coding=new CodingFacade(props,store,files,processes,tools,new CodingContext(json,files,store,props),new ModelLoop(client),settings,json,workspaces,agent,bindings,authority);
        var events=new RuntimeEventBus(new RuntimeEventStore(jdbc,json),new DataSourceTransactionManager(source));
        taskTools=new RoundtableTaskTools(json,board,mock(AgentDirectory.class),events,new CollaborationStore(jdbc),mock(CollaborationIssueActions.class),plans,new RoundtableMessageStore(jdbc),mock(CollaborationScopeGuard.class),coding,new CollaborationQuality(jdbc,json),tx,new cc.ginpika.minis.project.ProjectKnowledgeStore(jdbc));
        String started=AgentScope.run("author",()->coding.assistantTool("alice",json.createObjectNode().put("action","start").put("mode","EXECUTE")
                .put("repositoryId",repository.id()).put("prompt","只实现一个函数，并用 run_validation 绑定真实构建和测试证据"),"fixture-start",session));
        var parsed=json.readTree(started);runId=parsed.path("run").path("id").asText();
        assertFalse(runId.isBlank(),started);
        var finished=json.readTree(coding.assistantTool("alice",json.createObjectNode().put("action","read").put("runId",runId),null,session));
        assertEquals("REVIEWABLE",finished.path("status").asText(),finished.toString());
        assertEquals("PASSED",finished.path("validationStatus").asText(),finished.toString());
    }
    @AfterEach void close(){if(coding!=null)coding.close();}
    ObjectNode call(String name,ObjectNode arguments){var call=json.createObjectNode().put("id",UUID.randomUUID().toString());call.putObject("function").put("name",name).put("arguments",arguments.toString());return call;}
    OpenAiChatClient.RawResult response(ObjectNode call){var message=json.createObjectNode().put("role","assistant");message.putArray("tool_calls").add(call);return new OpenAiChatClient.RawResult(message,"raw","tool_calls");}
    String task(String name,String peer,ObjectNode arguments){try(var actor=UserScope.bind("alice")){return taskTools.runTool(name,call(name,arguments),session,peer,null,board.todo(todo).orElseThrow());}}
    void submitForReview(){
        assertTrue(task("complete_todo","author",json.createObjectNode().put("id",todo).put("result","编码副本 "+runId+"，实际构建与测试已通过")).contains("提交检查"));
        plans.checking(session,todo,plans.find(session).orElseThrow().version());board.claimAssigned(session,"reviewer",todo,true).orElseThrow();
    }
    void verifyCopy(){
        String report=AgentScope.run("reviewer",()->coding.assistantTool("alice",json.createObjectNode().put("action","verify").put("runId",runId),null,session));
        assertTrue(report.contains("\"status\":\"PASSED\""),report);
    }
    @Test void authorBuildIndependentRerunAndAcceptedApplicationUseOneActualCopy() throws Exception {
        assertFalse(Files.exists(project.resolve("core.js")),"提交前仅修改副本");
        assertEquals(1,commandExecutions.get());
        submitForReview();
        assertTrue(task("review_todo","reviewer",json.createObjectNode().put("id",todo).put("accept",true).put("evidence","仅看了自述")).contains("verify"));
        assertEquals("checking",board.todo(todo).orElseThrow().status());
        String selfReview=AgentScope.run("author",()->coding.assistantTool("alice",json.createObjectNode().put("action","verify").put("runId",runId),null,session));
        assertTrue(selfReview.contains("另一位检查者"));
        verifyCopy();assertEquals(2,commandExecutions.get(),"独立检查必须实际复跑，不能命中执行者缓存");
        assertTrue(task("review_todo","reviewer",json.createObjectNode().put("id",todo).put("accept",true).put("evidence","已读 diff 并独立执行相同构建和 Node 测试，退出码为 0")).contains("检查通过"));
        assertEquals("done",board.todo(todo).orElseThrow().status());assertEquals("COMPLETED",coding.require("alice",runId).status());
        assertTrue(Files.readString(project.resolve("core.js")).contains("a+b"));
    }
    @Test void aChangedCopyCannotBeAppliedWithAnOlderReview() throws Exception {
        submitForReview();verifyCopy();
        var patches=json.createArrayNode().addObject().put("path","extra.js").put("expectedHash","MISSING").put("newText","export const changed=true;\n");
        files.patch(runId,json.createArrayNode().add(patches));
        assertTrue(task("review_todo","reviewer",json.createObjectNode().put("id",todo).put("accept",true).put("evidence","旧版本通过")).contains("verify"));
        assertFalse(Files.exists(project.resolve("core.js")));assertEquals("checking",board.todo(todo).orElseThrow().status());
    }
    @Test void verificationReturnsAStructuredConflictBeforeOverwritingEitherCopy() throws Exception {
        submitForReview();Files.writeString(project.resolve("core.js"),"export const add=()=>42;\n");
        var report=json.readTree(AgentScope.run("reviewer",()->coding.assistantTool("alice",json.createObjectNode().put("action","verify").put("runId",runId),null,session)));
        assertEquals("APPLICATION_CONFLICT",report.path("code").asText(),report.toString());
        assertEquals(runId,report.path("runId").asText());assertFalse(report.path("artifactHash").asText().isBlank());
        assertTrue(Files.readString(project.resolve("core.js")).contains("42"));
        assertTrue(Files.readString(files.checkout(runId).resolve("core.js")).contains("a+b"));
        assertNull(authority.link(runId).orElseThrow().reviewedHash());
    }
    @Test void independentReviewBuildsAndValidatesTheIntegratedTree() throws Exception {
        String oldHash=coding.require("alice",runId).artifactHash();submitForReview();
        Files.writeString(project.resolve("README.md"),"accepted parallel documentation\n");
        verifyCopy();
        var integrated=coding.require("alice",runId);
        assertNotEquals(oldHash,integrated.artifactHash());
        assertEquals(files.treeHash(runId),store.validationHash(runId));
        assertEquals("accepted parallel documentation\n",Files.readString(files.checkout(runId).resolve("README.md")));
        assertEquals(2,commandExecutions.get());
        assertTrue(task("review_todo","reviewer",json.createObjectNode().put("id",todo).put("accept",true).put("evidence","已读整合 diff 并独立复跑构建与测试")).contains("检查通过"));
        assertTrue(Files.exists(project.resolve("core.js")));
        assertEquals("accepted parallel documentation\n",Files.readString(project.resolve("README.md")));
    }
    @Test void applyingAgainstChangedProjectHandsBackDiagnosisAndPreservesBothCopies() throws Exception {
        submitForReview();verifyCopy();String hash=coding.require("alice",runId).artifactHash();
        Files.writeString(project.resolve("core.js"),"user change\n");
        String result=task("review_todo","reviewer",json.createObjectNode().put("id",todo).put("accept",true).put("evidence","精确版本复核通过"));
        assertTrue(result.contains("APPLICATION_CONFLICT"),result);
        assertEquals("blocked",board.todo(todo).orElseThrow().status());
        assertEquals("user change\n",Files.readString(project.resolve("core.js")));
        assertTrue(Files.readString(files.checkout(runId).resolve("core.js")).contains("a+b"));
        var diagnosis=new CollaborationQuality(jdbc,json).find(todo).diagnosis();
        assertEquals(runId,diagnosis.path("runId").asText());assertEquals(hash,diagnosis.path("artifactHash").asText());
        assertEquals("REVIEWABLE",coding.require("alice",runId).status());
    }
    @Test void exactAlreadyAppliedCopyCanBeConfirmedAfterIndependentVerification() throws Exception {
        submitForReview();verifyCopy();
        var diff=files.diff(runId);
        for(Change change:diff.changes())Files.copy(files.checkout(runId).resolve(change.path()),project.resolve(change.path()));
        String result=task("review_todo","reviewer",json.createObjectNode().put("id",todo).put("accept",true).put("evidence","独立复跑通过，原项目已与受检产物完全一致"));
        assertTrue(result.contains("检查通过"),result);assertEquals("done",board.todo(todo).orElseThrow().status());
        assertTrue(Files.isRegularFile(files.task(runId).resolve("application-confirmed.json")));
        assertEquals(diff.artifactHash(),json.readTree(Files.readString(files.task(runId).resolve("application-confirmed.json"))).path("artifactHash").asText());
        assertTrue(store.events(runId,0,500).stream().anyMatch(e->e.type().equals("artifact.accepted") && e.payload().contains("\"confirmedExistingContent\":true")));
    }
    @Test void reworkPromptReachesTheActualModelContextAndHasAnAppliedReceipt() throws Exception {
        String instruction="只修正 Hz 表格和 rAF 示例；保留其他内容";
        String response=AgentScope.run("author",()->coding.assistantTool("alice",json.createObjectNode().put("action","resume").put("runId",runId).put("prompt",instruction).put("mode","EXECUTE"),"rework-1",session));
        var ack=json.readTree(response);assertEquals(instruction,ack.path("receipt").path("acceptedInput").asText(),response);
        assertTrue(ack.path("normalizedPromptToInput").asBoolean());
        var completed=json.readTree(coding.assistantTool("alice",json.createObjectNode().put("action","read").put("runId",runId),null,session));
        assertEquals("APPLIED",completed.path("instruction").path("instructionState").asText());
        assertEquals("COLLABORATION_AGENT",completed.path("instruction").path("source").asText());
        assertTrue(store.messages(runId).contains(instruction));assertTrue(store.messages(runId).contains("来源=COLLABORATION_AGENT"));
        int attempt=coding.require("alice",runId).attempt();
        String replay=AgentScope.run("author",()->coding.assistantTool("alice",json.createObjectNode().put("action","resume").put("runId",runId).put("input",instruction),"rework-1",session));
        assertTrue(json.readTree(replay).path("replayed").asBoolean(),replay);
        assertEquals(attempt,coding.require("alice",runId).attempt());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM coding_input WHERE run_id=?",Integer.class,runId));
    }
    @Test void invalidResumeCannotEraseReviewOrAdvanceAttempt() throws Exception {
        authority.reviewed(runId,authority.executor("alice",session,"author"),"reviewer","checked","evidence");
        var before=authority.link(runId).orElseThrow();
        Files.writeString(files.task(runId).resolve("accept-journal.json"),"{}");
        String response=AgentScope.run("author",()->coding.assistantTool("alice",json.createObjectNode().put("action","resume").put("runId",runId).put("input","修复"),"invalid-resume",session));
        assertTrue(response.contains("接受日志"),response);
        assertEquals(before,authority.link(runId).orElseThrow());assertEquals(1,coding.require("alice",runId).attempt());
        assertTrue(store.pendingInputs(runId).isEmpty());
        assertTrue(AgentScope.run("author",()->coding.assistantTool("alice",json.createObjectNode().put("action","resume").put("runId",runId).put("input","A").put("prompt","B"),"conflict",session)).contains("INVALID_ARGUMENT"));
        assertEquals(before,authority.link(runId).orElseThrow());
    }
    @Test void unchangedArtifactWithRepeatedBlockersReturnsToDiagnosis() throws Exception {
        submitForReview();verifyCopy();
        var findings=json.createArrayNode();findings.addObject().put("id","F1").put("severity","BLOCKER").put("location","core.test.js").put("expected","补齐边界用例").put("basis","C1 的输入边界验收");
        var review=json.createObjectNode().put("id",todo).put("accept",false).put("evidence","边界用例缺失");review.set("findings",findings);
        assertTrue(task("review_todo","reviewer",review).contains("返工"));
        int version=plans.find(session).orElseThrow().version();plans.returned(session,todo,"请原条目返工",version);
        plans.dispatch(session,todo,"author","reviewer","executing","补齐用例");board.claimAssigned(session,"author",todo,false).orElseThrow();
        submitForReview();
        assertTrue(task("review_todo","reviewer",review).contains("NEEDS_DIAGNOSIS"));
        assertEquals("blocked",board.todo(todo).orElseThrow().status());
        assertEquals("NEEDS_DIAGNOSIS",new CollaborationQuality(jdbc,json).find(todo).diagnosis().path("code").asText());
        assertEquals(1,coding.require("alice",runId).attempt(),"诊断不应自动开启新一轮编码");
    }
    @Test void ownerAssignmentAndBindingRevocationDoNotGrantBroaderExecution(){
        assertTrue(authority.assignment("bob",session).isEmpty());
        assertThrows(IllegalArgumentException.class,()->authority.executor("alice",session,"reviewer"));
        jdbc.update("UPDATE hub_session SET member_ids='[\"reviewer\"]' WHERE id=?",session);
        assertTrue(authority.assignment("alice",session).isEmpty());
        assertThrows(CancellationException.class,()->authority.assertLive(runId));
        jdbc.update("UPDATE hub_session SET member_ids='[\"author\",\"reviewer\"]' WHERE id=?",session);
        plans.reopen(session);assertThrows(CancellationException.class,()->authority.assertLive(runId));
    }

    @Test void directoryRebindingExcludesOldCopiesFromNewWorkAndAcceptance() throws Exception {
        var migrated=store.register("alice","migrated",temp.resolve("new-project").toString(),null,null);
        new ProjectWorkspaceStore(jdbc).bind("project",migrated.id());plans.reopen(session);
        assertTrue(authority.runs(session,todo).isEmpty());
        assertTrue(authority.link(runId).isPresent(),"旧目录的副本与关联记录仍保留");
        assertNotNull(store.run(runId,"alice"));
        plans.dispatch(session,todo,"author","reviewer","executing","目录迁移后重新执行");
        var listed=json.readTree(AgentScope.run("author",()->coding.assistantTool("alice",json.createObjectNode().put("action","list"),null,session)));
        assertTrue(listed.path("executionAuthorized").asBoolean(),listed.toString());
        assertTrue(listed.path("collaborationRuns").isEmpty(),listed.toString());
        assertEquals("",coding.collaborationSubmission("alice",session,"author",todo),"旧副本不应阻止新目录提交成果");
        assertThrows(IllegalArgumentException.class,()->authority.continueRun("alice",session,"author",runId));
        assertThrows(CancellationException.class,()->authority.assertLive(runId));
        board.transition(todo,session,"doing","reviewing",null,null);
        plans.checking(session,todo,plans.find(session).orElseThrow().version());
        board.claimAssigned(session,"reviewer",todo,true).orElseThrow();
        assertThrows(IllegalArgumentException.class,()->authority.reviewer("alice",session,"reviewer",runId));
        assertEquals("",coding.applyCollaboration("alice",session,"reviewer",todo));
        assertFalse(Files.exists(project.resolve("core.js")),"旧目录不能收到迁移后的应用操作");
    }
}
