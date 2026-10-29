package cc.ginpika.minis.agent.roundtable;

import cc.ginpika.minis.project.ProjectKnowledgeStore;
import cc.ginpika.minis.project.ProjectStore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class ProjectKnowledgeTest {
    static class Fixture implements AutoCloseable {
        final SingleConnectionDataSource ds=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);
        final JdbcTemplate jdbc=new JdbcTemplate(ds);
        final ProjectKnowledgeStore knowledge=new ProjectKnowledgeStore(jdbc);
        final RoundtableSessionStore sessions=new RoundtableSessionStore(jdbc);
        final CollaborationPlan plans=new CollaborationPlan(jdbc,knowledge);
        Fixture() throws Exception {
            ScriptUtils.executeSqlScript(ds.getConnection(),new ClassPathResource("schema.sql"));
            jdbc.execute("PRAGMA foreign_keys=ON");
            var projects=new ProjectStore(jdbc);
            for(String id:List.of("p1","p2")) projects.insert(id,null,id,"software",false,"active","长期方向");
        }
        String session(String project) {
            String session=sessions.insert("[\"a\",\"b\"]");
            jdbc.update("UPDATE hub_session SET project_id=?,title='阶段成果' WHERE id=?",project,session);
            plans.create(session,"a");return session;
        }
        ProjectKnowledgeStore.Write write(String text,int version) { return new ProjectKnowledgeStore.Write("overview","项目现状",text,version,false); }
        void finish(String session) {
            plans.revise(session,"实际运行通过","选用已有接口",new CollaborationPlan.Scope("接口改造","暂不处理客户端","已实查接口与测试","下一步验证重连"));
            assertTrue(plans.finish(session,"接口测试通过；成果位置：src/server.java；客户端未验证"));
        }
        public void close() { ds.destroy(); }
    }

    @Test void projectDocumentsHaveImmutableHistoryAndRejectStaleAndCrossProjectWrites() throws Exception {
        try(var f=new Fixture()) {
            String source=f.session("p1"), foreign=f.session("p2");
            f.knowledge.save("p1","overview",f.write("已完成接口，下一步验证客户端",0),source,"a");
            f.knowledge.save("p1","overview",f.write("已验证接口与客户端",1),null,"user:alice");
            assertEquals("已完成接口，下一步验证客户端",f.knowledge.read("p1","overview",1).content());
            assertEquals(2,f.knowledge.read("p1","overview",null).version());
            assertEquals(2,f.knowledge.history("p1","overview").size());
            assertTrue(f.knowledge.history("p1","overview").getFirst().content().isEmpty());
            var conflict=assertThrows(ResponseStatusException.class,()->f.knowledge.save("p1","overview",f.write("覆盖新内容",1),source,"a"));
            assertEquals(409,conflict.getStatusCode().value());
            assertThrows(ResponseStatusException.class,()->f.knowledge.save("p1","foreign",f.write("错误归属",0),foreign,"b"));
            assertThrows(ResponseStatusException.class,()->f.knowledge.read("p2","overview",null));
            assertTrue(f.knowledge.context(foreign).contains("项目文档 0 份"));
            assertFalse(f.knowledge.context(foreign).contains("已验证接口与客户端"));
            f.jdbc.update("UPDATE hub_session SET project_id='p2' WHERE id=?",source);
            assertFalse(f.knowledge.context(source).contains("已验证接口与客户端"),"改绑后按当前项目重新检查可见范围");
            assertEquals("已验证接口与客户端",f.knowledge.read("p1","overview",null).content(),"已发布成果仍归属原项目");
        }
    }

    @Test void deliverySurvivesSessionDeletionAndFutureSessionsCanDiscoverOlderResultsBeyondThree() throws Exception {
        try(var f=new Fixture()) {
            String source=f.session("p1");
            String todo=new RoundtableTodoStore(f.jdbc).add(source,null,"a","实现接口","保留已有行为","a",null);
            f.jdbc.update("UPDATE roundtable_todo SET status='done',result='独立检查通过：src/server.java' WHERE id=?",todo);
            f.finish(source);
            for(int i=0;i<4;i++) f.finish(f.session("p1"));
            assertEquals(5,f.knowledge.list("p1","",false,0,20).total());
            String target=f.session("p1");
            var context=new RoundtableContextBuilder(null,null,null,new RoundtableMessageStore(f.jdbc),new RoundtableTodoStore(f.jdbc),new cc.ginpika.minis.config.RoundtableProps(),f.knowledge);
            assertTrue(context.pinnedContext(target).contains("项目文档 5 份"));
            assertTrue(context.dispatch(target,5000,null,"b").contains("项目文档 5 份"));
            assertTrue(context.conversationTranscript(target,5000).contains("项目文档 5 份"));
            f.jdbc.update("DELETE FROM collaboration_plan WHERE session_id=?",target);
            assertTrue(context.pinnedContext(target).contains("项目文档 5 份"),"历史协作会话也能承接项目资料");
            var doc=f.knowledge.list("p1","",false,0,20).items().stream().filter(d->source.equals(d.sourceSessionId())).findFirst().orElseThrow();
            assertTrue(f.knowledge.read("p1",doc.key(),null).content().contains("独立检查通过"));
            assertTrue(f.knowledge.read("p1",doc.key(),null).content().contains("客户端未验证"));
            f.sessions.delete(source);
            assertEquals(source,f.knowledge.read("p1",doc.key(),null).sourceSessionId());
            assertTrue(f.knowledge.context(target).contains(doc.key()));
            assertFalse(f.knowledge.context(f.session("p2")).contains(doc.key()));
        }
    }

    @Test void progressReflectsCurrentWorkAndExcludesArchivedAndOtherProjects() throws Exception {
        try(var f=new Fixture()) {
            String first=f.session("p1"), archived=f.session("p1"), other=f.session("p2");
            var board=new RoundtableTodoStore(f.jdbc);
            String todo=board.add(first,null,"a","等待协议确认","具体范围","a",null);
            f.jdbc.update("UPDATE roundtable_todo SET status='blocked',block_reason='缺少版本约定' WHERE id=?",todo);
            board.add(archived,null,"a","不应作为当前工作","","a",null);
            board.add(other,null,"a","其他项目私有工作","","a",null);
            f.jdbc.update("INSERT INTO collaboration_task_archive VALUES(?,?)",archived,1);
            var progress=f.knowledge.progress("p1",0,20);
            assertEquals(1,progress.openTodos());assertEquals(1,progress.blockedTodos());
            assertEquals("缺少版本约定",progress.work().items().getFirst().blocker());
            assertFalse(f.knowledge.context(first).contains("其他项目私有工作"));
            f.jdbc.update("UPDATE roundtable_todo SET status='done' WHERE id=?",todo);
            assertEquals(0,f.knowledge.progress("p1",0,20).openTodos());
        }
    }

    @Test void retiredDocumentsAreDiscoverableExplicitlyAndSearchAndPaginationIncludeOldKnowledge() throws Exception {
        try(var f=new Fixture()) {
            for(int i=0;i<24;i++) f.knowledge.save("p1","decision-"+i,new ProjectKnowledgeStore.Write("decision","存储决策 "+i,"依据：保留数据；决定：SQLite",0,false),null,"user:alice");
            f.knowledge.save("p1","old",new ProjectKnowledgeStore.Write("reference","已替代方案","旧实现",0,true),null,"user:alice");
            assertEquals(24,f.knowledge.list("p1","SQLite",false,0,20).total());
            assertEquals(4,f.knowledge.list("p1","SQLite",false,20,20).items().size());
            assertEquals(25,f.knowledge.list("p1","",true,0,50).total());
            assertFalse(f.knowledge.context(f.session("p1")).contains("旧实现"));
            assertTrue(f.knowledge.read("p1","old",null).retired());
        }
    }

    @Test void legacyDeliveriesAreImportedOnceWithoutOverwritingUserRevisions() throws Exception {
        try(var f=new Fixture()) {
            String source=f.session("p1");
            f.jdbc.update("UPDATE collaboration_plan SET phase='completed',evidence='历史检查证据' WHERE session_id=?",source);
            ScriptUtils.executeSqlScript(f.ds.getConnection(),new ClassPathResource("schema.sql"));
            var entry=f.knowledge.list("p1","",false,0,20).items().getFirst();
            assertTrue(f.knowledge.read("p1",entry.key(),null).content().contains("历史检查证据"));
            f.knowledge.save("p1",entry.key(),new ProjectKnowledgeStore.Write("delivery","补充后的成果","补充限制：缺少运行验证",1,false),null,"user:alice");
            ScriptUtils.executeSqlScript(f.ds.getConnection(),new ClassPathResource("schema.sql"));
            assertEquals(1,f.knowledge.list("p1","",false,0,20).total());
            assertEquals(2,f.knowledge.read("p1",entry.key(),null).version());
        }
    }

    @Test void onlyTheMainCoordinatorPublishesButWorkersCanReadWithoutChangingTaskState() throws Exception {
        try(var f=new Fixture()) {
            String session=f.session("p1"), foreign=f.session("p2");
            f.knowledge.save("p2","secret",f.write("别的项目资料",0),foreign,"a");
            var directory=org.mockito.Mockito.mock(cc.ginpika.minis.agent.member.AgentDirectory.class);
            var a=org.mockito.Mockito.mock(cc.ginpika.minis.agent.member.AgentStore.AgentRow.class);
            var b=org.mockito.Mockito.mock(cc.ginpika.minis.agent.member.AgentStore.AgentRow.class);
            org.mockito.Mockito.when(a.id()).thenReturn("a");org.mockito.Mockito.when(b.id()).thenReturn("b");
            org.mockito.Mockito.when(directory.peers(session)).thenReturn(List.of(a,b));
            var om=new com.fasterxml.jackson.databind.ObjectMapper();
            var tools=new RoundtableTaskTools(om,null,directory,null,new CollaborationStore(f.jdbc),null,f.plans,null,null,null,null,null,f.knowledge);
            var call=om.createObjectNode();
            call.putObject("function").put("arguments","{\"key\":\"overview\",\"kind\":\"overview\",\"title\":\"现状\",\"content\":\"接口已验证\",\"expected_version\":0}");
            assertTrue(tools.runTool("write_project_knowledge",call,session,"b",null,null).contains("只有主会话的主控"));
            assertTrue(tools.runTool("write_project_knowledge",call,session,"outsider",null,null).contains("不是当前"));
            assertTrue(tools.runTool("write_project_knowledge",call,session,"a",null,null).contains("接口已验证"));
            call.withObject("function").put("arguments","{\"action\":\"read\",\"key\":\"overview\"}");
            var board=new RoundtableTodoStore(f.jdbc);
            String working=board.add(session,null,"b","验证接口","范围","a",null);
            assertTrue(tools.runTool("read_project_knowledge",call,session,"b",null,board.todo(working).orElseThrow()).contains("接口已验证"));
            call.withObject("function").put("arguments","{\"action\":\"read\",\"key\":\"secret\",\"project_id\":\"p2\"}");
            assertTrue(tools.runTool("read_project_knowledge",call,session,"a",null,null).contains("不存在"));
            assertEquals(1,f.knowledge.list("p1","",false,0,20).total());
        }
    }
}
