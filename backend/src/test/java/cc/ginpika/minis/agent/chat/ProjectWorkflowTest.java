package cc.ginpika.minis.agent.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import cc.ginpika.minis.agent.chat.store.*;
import cc.ginpika.minis.ai.core.*;
import cc.ginpika.minis.ai.memory.*;
import cc.ginpika.minis.ai.memory.recall.MemoryItemRecall;
import cc.ginpika.minis.ai.memory.store.*;
import cc.ginpika.minis.config.MemoryProps;
import cc.ginpika.minis.project.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Real SQLite/schema tests for continuation authorization and newest material. */
class ProjectWorkflowTest {
    static class Fixture implements AutoCloseable {
        final SingleConnectionDataSource ds=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);
        final JdbcTemplate jdbc=new JdbcTemplate(ds);
        final ObjectMapper om=new ObjectMapper();
        final HubSessionStore sessions=new HubSessionStore(jdbc);
        final ChatMessageStore messages=new ChatMessageStore(jdbc);
        final ProjectStore projects=new ProjectStore(jdbc);
        final ProjectProgressStore progress=new ProjectProgressStore(jdbc);
        final ProjectWorkflowService service=new ProjectWorkflowService(projects,sessions,progress,jdbc,om);
        Fixture() throws Exception {
            ScriptUtils.executeSqlScript(ds.getConnection(),new ClassPathResource("schema.sql"));
            service.setTransactions(new DataSourceTransactionManager(ds));
        }
        void session(String id,String agent,String project) {
            sessions.insert(id,id,project);
            sessions.updateConfig(id,null,null,agent,true,false,30,false);
        }
        long pair(String id,String question,String answer) {
            messages.insert(id,"user",question,null,null,null,null,null);
            return messages.insert(id,"assistant",answer,null,null,null,null,null);
        }
        MemoryExec exec(String id) {
            var s=sessions.findById(id).orElseThrow();
            return MemoryExec.chat(s.agentId(),s.projectId(),id,null,projects.isNarrative(s.projectId()));
        }
        public void close() { ds.destroy(); MemoryLiveContext.clear(); MemoryLiveContext.takeCaptured(); }
    }

    @Test void newContinuationPreservesAgentAndReadsLatestBeforeExtraction() throws Exception {
        try(var f=new Fixture()) {
            f.session("source","agent",null);
            long latest=f.pair("source","最新章节进度","第一幕已完成，下一步准备第二幕。");
            var next=f.service.continueInNewSession("source");
            assertEquals("agent",next.agentId());
            var material=f.progress.visible(f.exec(next.id()));
            assertEquals(1,material.size());
            assertTrue(material.get(0).messageIds().contains(latest));
            assertTrue(material.get(0).body().contains("下一步准备第二幕"));
            assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM memory_item",Integer.class));
            f.pair("source","新进展","第二幕也已经完成");
            assertTrue(f.progress.visible(f.exec(next.id())).get(0).body().contains("第二幕也已经完成"));
        }
    }

    @Test void sameProjectAndAgentCanContinueButOtherAgentsAndProjectsCannot() throws Exception {
        try(var f=new Fixture()) {
            var p=f.service.createProject("作品","story",null,null);
            var q=f.service.createProject("研究","research",null,null);
            f.session("source","a",p.id()); f.pair("source","进度","最后一段的新进展");
            f.session("same","a",p.id()); f.session("otherAgent","b",p.id()); f.session("otherProject","a",q.id());
            assertTrue(f.progress.allowed(f.exec("same"),"source"));
            assertFalse(f.progress.allowed(f.exec("otherAgent"),"source"));
            assertFalse(f.progress.allowed(f.exec("otherProject"),"source"));
            assertTrue(f.progress.visible(f.exec("same")).get(0).body().contains("虚构设定"));
            assertThrows(Exception.class,()->f.service.link("otherProject","source","a"));
        }
    }

    @Test void organizingOldSessionDoesNotRewriteHistoricalMemoryOrCreateSharedAuthority() throws Exception {
        try(var f=new Fixture()) {
            f.session("old","a",null); f.pair("old","写作进度","最后一段");
            var items=new MemoryItemStore(f.jdbc,new MemoryRevisionStore(f.jdbc));
            long memory=items.insert(new MemoryItemStore.Ownership(null,"TASK","TASK_EPISODIC","L3",null,"old",9L,"a"),
                    new MemoryItemStore.Governance("a","inferred","inferred","normal",MemoryIdentity.REAL),
                    "decision","进度","保留旧归属",0.9,"auto",null,null);
            var p=f.service.organize("old","作品","novel",null);
            assertTrue(p.narrative());
            assertEquals(p.id(),f.sessions.findById("old").orElseThrow().projectId());
            var original=items.findById(memory).orElseThrow();
            assertEquals("L3",original.layer()); assertNull(original.projectId()); assertEquals("old",original.sessionId());
            var target=f.service.continueInNewSession("old");
            assertTrue(items.visible(f.exec(target.id()),null,50).isEmpty());
            assertTrue(f.progress.visible(f.exec(target.id())).get(0).body().contains("最后一段"));
        }
    }

    @Test void revocationMemoryOffAndRebindingTakeEffectOnEveryRead() throws Exception {
        try(var f=new Fixture()) {
            var p=f.service.createProject("项目","other",null,null);
            f.session("source","a",p.id()); f.pair("source","状态","进度");
            var target=f.service.continueInNewSession("source"); var exec=f.exec(target.id());
            f.progress.revoke(target.id(),"source","a");
            assertFalse(f.progress.allowed(exec,"source"));
            f.service.link(target.id(),"source","a"); assertTrue(f.progress.allowed(exec,"source"));
            f.sessions.setMemoryOff("source",true); assertFalse(f.progress.allowed(exec,"source"));
            assertThrows(Exception.class,()->f.service.continueInNewSession("source"));
            f.sessions.setMemoryOff("source",false);
            f.service.bind(target.id(),null); assertFalse(f.progress.allowed(exec,"source"));
        }
    }

    @Test void stoppingAllContinuationCanRestoreOnlySelectedSource() throws Exception {
        try(var f=new Fixture()) {
            var p=f.service.createProject("项目","other",null,null);
            for(String id:List.of("s1","s2","target")) { f.session(id,"a",p.id()); f.pair(id,"问题","进度"); }
            f.service.revokeAll("target");
            assertTrue(f.progress.visible(f.exec("target")).isEmpty());
            f.service.link("target","s1","a");
            assertTrue(f.progress.allowed(f.exec("target"),"s1"));
            assertFalse(f.progress.allowed(f.exec("target"),"s2"));
        }
    }

    @Test void toolSuggestionsNeverCreateAndExplicitRequestCreatesWithoutDuplicate() throws Exception {
        try(var f=new Fixture()) {
            f.session("chat","a",null); f.pair("chat","你建议怎么管理这段工作？","可以建个项目");
            var args=f.om.readTree("{\"action\":\"create\",\"name\":\"作品\",\"kind\":\"novel\"}");
            String denied=MemoryScopeCtx.run(f.exec("chat"),()->f.service.runTool("chat",args));
            assertTrue(denied.contains("明确要求")); assertTrue(f.projects.list().isEmpty());
            var suggest=f.om.readTree("{\"action\":\"suggest\",\"name\":\"作品\"}");
            MemoryScopeCtx.run(f.exec("chat"),()->f.service.runTool("chat",suggest));
            assertTrue(f.projects.list().isEmpty());
            f.messages.insert("chat","user","把这个剧本创建为项目，名字叫作品",null,null,null,null,null);
            String result=MemoryScopeCtx.run(f.exec("chat"),()->f.service.runTool("chat",args));
            assertTrue(result.contains("boundSessionId"),result);
            String pid=f.sessions.findById("chat").orElseThrow().projectId();
            assertNotNull(pid); assertTrue(f.projects.isNarrative(pid));
            assertEquals(pid,f.service.createProject("作品","novel",null,null).id());
            assertEquals(1,f.projects.list().size());
            assertFalse(ProjectWorkflowService.explicitRequest("不要创建项目","create"));
            assertFalse(ProjectWorkflowService.explicitRequest("怎么创建项目？","create"));
        }
    }

    @Test void snapshotRecordsTailAndLiveRevocationRemovesItBeforeNextModelRequest() throws Exception {
        try(var f=new Fixture()) {
            f.session("source","a",null);
            f.pair("source","进度","开头信息"+"很长的正文".repeat(1400)+"最后关键进度");
            var target=f.service.continueInNewSession("source"); var exec=f.exec(target.id());
            var snapshots=new MemorySnapshotStore(f.jdbc);
            var recall=mock(MemoryItemRecall.class);
            when(recall.recall(any(),anyString(),isNull(),anyInt())).thenReturn(List.of());
            var working=mock(WorkingSetService.class);
            when(working.open(any(),anyInt())).thenReturn(List.of());
            var builder=new MemorySnapshotBuilder(recall,snapshots,working,new MemoryProps()); builder.setProgress(f.progress);
            var snap=builder.build(exec,"继续","turn");
            assertFalse(snap.progress().isEmpty());
            assertTrue(snap.progress().get(0).body().contains("最后关键进度"));
            var stored=snapshots.find("turn").orElseThrow();
            assertTrue(stored.contentJson().contains("最后关键进度"));
            var restored=f.om.readValue(stored.contentJson(),MemorySnapshot.class);
            assertEquals(snap.progress(),restored.progress());
            var message=f.om.createObjectNode(); message.put("role","system"); message.put("content",MemoryLiveContext.START+"\n"+MemoryLiveContext.END);
            message.set("_minis_memory",f.om.valueToTree(snap));
            var messages=f.om.createArrayNode().add(message); MemoryLiveContext.begin(messages);
            var live=new MemoryLiveContext(new MemoryItemStore(f.jdbc,new MemoryRevisionStore(f.jdbc)),new MemoryWorkStore(f.jdbc));
            live.setProgress(f.progress);
            MemoryScopeCtx.runVoid(exec,()->live.refresh(messages));
            assertTrue(messages.toString().contains("最后关键进度"));
            f.service.revokeAll(target.id()); MemoryScopeCtx.runVoid(exec,()->live.refresh(messages));
            assertFalse(messages.toString().contains("最后关键进度"));
        }
    }

    @Test void shortConfirmationMustMatchSpecificProposalAndCannotBeReused() throws Exception {
        try(var f=new Fixture()) {
            f.session("chat","a",null); f.pair("chat","你建议如何组织工作？","我可以提出一个项目建议");
            var suggestion=f.om.readTree("{\"action\":\"suggest\",\"name\":\"作品\",\"kind\":\"story\"}");
            MemoryScopeCtx.run(f.exec("chat"),()->f.service.runTool("chat",suggestion));
            assertTrue(f.projects.list().isEmpty());
            f.messages.insert("chat","user","好",null,null,null,null,null);
            var wrong=f.om.readTree("{\"action\":\"create\",\"name\":\"其他项目\",\"kind\":\"story\"}");
            assertTrue(MemoryScopeCtx.run(f.exec("chat"),()->f.service.runTool("chat",wrong)).contains("明确要求"));
            var accepted=f.om.readTree("{\"action\":\"create\",\"name\":\"作品\",\"kind\":\"story\"}");
            assertTrue(MemoryScopeCtx.run(f.exec("chat"),()->f.service.runTool("chat",accepted)).contains("boundSessionId"));
            assertEquals(1,f.projects.list().size());
            assertTrue(MemoryScopeCtx.run(f.exec("chat"),()->f.service.runTool("chat",wrong)).contains("明确要求"));
            assertFalse(ProjectWorkflowService.explicitRequest("我们应该创建项目吗？","create"));
        }
    }

    @Test void toolCannotReadUnlinkedSourceOrAnotherSessionsMessageAndCanPageLongMessage() throws Exception {
        try(var f=new Fixture()) {
            f.session("source","a",null); long message=f.pair("source","进度","前文".repeat(6000)+"结尾线索");
            f.session("target","a",null); f.session("secret","a",null); long secret=f.pair("secret","秘密","不应读取");
            var read=f.om.readTree("{\"action\":\"read\",\"source_session_id\":\"source\",\"message_id\":"+message+",\"offset\":12000}");
            assertTrue(MemoryScopeCtx.run(f.exec("target"),()->f.service.runTool("target",read)).contains("尚未授权"));
            f.service.link("target","source","a");
            assertTrue(MemoryScopeCtx.run(f.exec("target"),()->f.service.runTool("target",read)).contains("结尾线索"));
            var illegal=read.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)illegal).put("message_id",secret);
            assertTrue(MemoryScopeCtx.run(f.exec("target"),()->f.service.runTool("target",illegal)).contains("不存在这条消息"));
            f.sessions.setMemoryOff("target",true);
            assertTrue(MemoryScopeCtx.run(f.exec("target"),()->f.service.runTool("target",read)).contains("尚未授权"));
        }
    }

    @Test void archivedProjectAndDeletedSourceInvalidateExistingGrants() throws Exception {
        try(var f=new Fixture()) {
            var p=f.service.createProject("项目","other",null,null);
            f.session("source","a",p.id()); f.pair("source","问题","最新进度");
            var target=f.service.continueInNewSession("source");
            f.projects.update(p.id(),null,null,null,"archived",null);
            assertFalse(f.progress.allowed(f.exec(target.id()),"source"));
            f.projects.update(p.id(),null,null,null,"active",null);
            f.messages.deleteBySession("source");
            f.sessions.delete("source");
            assertTrue(f.progress.visible(f.exec(target.id())).isEmpty());
        }
    }

    @Test void toolTransactionRollsBackNewProjectWhenBindingFails() throws Exception {
        try(var f=new Fixture()) {
            f.session("chat","a",null); f.pair("chat","创建一个项目","好的");
            f.jdbc.execute("CREATE TRIGGER reject_binding BEFORE UPDATE OF project_id ON hub_session BEGIN SELECT RAISE(ABORT,'binding failed'); END");
            var request=f.om.readTree("{\"action\":\"create\",\"name\":\"项目\"}");
            String result=MemoryScopeCtx.run(f.exec("chat"),()->f.service.runTool("chat",request));
            assertTrue(result.contains("未完成"));
            assertTrue(f.projects.list().isEmpty());
            assertNull(f.sessions.findById("chat").orElseThrow().projectId());
        }
    }
}
