package cc.ginpika.minis.web.controller;

import static org.junit.jupiter.api.Assertions.*;
import cc.ginpika.minis.ai.core.*;
import cc.ginpika.minis.ai.memory.*;
import cc.ginpika.minis.ai.memory.store.*;
import cc.ginpika.minis.config.MemoryProps;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.server.ResponseStatusException;

/** 真 SQLite 观察接口：访问边界、历史事实与实际请求不能相互冒充。 */
class MemoryObservationControllerTest {
    static class Fixture implements AutoCloseable {
        final SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:",true);
        final JdbcTemplate jdbc = new JdbcTemplate(ds);
        final ObjectMapper json = new ObjectMapper();
        final MemoryItemStore items;
        final MemoryShareStore shares;
        final MemorySnapshotStore snapshots;
        final MemoryWorkStore works;
        final MemoryObservationController controller;
        Fixture() throws Exception {
            ScriptUtils.executeSqlScript(ds.getConnection(),new ClassPathResource("schema.sql"));
            items=new MemoryItemStore(jdbc,new MemoryRevisionStore(jdbc));
            shares=new MemoryShareStore(jdbc); snapshots=new MemorySnapshotStore(jdbc); works=new MemoryWorkStore(jdbc);
            controller=new MemoryObservationController(items,shares,snapshots,works,jdbc,json,new WorkingSetService(works,null,new MemoryProps()));
        }
        long item(String owner,String layer,String project,String agent,String session) {
            return items.insert(new MemoryItemStore.Ownership(null,owner,owner.equals("USER") ? "USER_PROFILE" : owner.equals("TASK") ? "TASK_EPISODIC" : owner,layer,project,session,null,agent),
                new MemoryItemStore.Governance(agent,"real","explicit","long",MemoryIdentity.REAL),"fact","title","旧正文",0.9,"manual",null,null);
        }
        void snapshot(String turn,String agent,String project,String session,long item) {
            snapshots.save(new MemorySnapshot(turn,MemoryExec.chat(agent,project,session,null,false),snapshots.watermark(),
                List.of(items.findById(item).orElseThrow()),List.of(),System.currentTimeMillis(),1000),12);
        }
        public void close() { ds.destroy(); MemoryLiveContext.clear(); MemoryLiveContext.takeCaptured(); }
    }
    @Test void visibilityMatchesRuntimeIncludingExplicitSharingAndIsReadOnly() throws Exception {
        try(var f=new Fixture()) {
            long global=f.item("USER","L1",null,null,null);
            long project=f.item("PROJECT","L1","p",null,null);
            long privateId=f.item("AGENT","L2","p","a",null);
            long bound=f.item("TASK","L3","p","a","s");
            long published=f.item("PROJECT","L4","q","a",null);
            f.shares.publish(published,"PROJECT","a","q","agent","b","测试");
            long watermark=f.snapshots.watermark();
            var b=f.controller.visibility("b","p",null,null);
            assertEquals(java.util.Set.of(global,project,published),b.entries().stream().map(MemoryObservationController.Access::itemId).collect(java.util.stream.Collectors.toSet()));
            assertTrue(b.entries().stream().filter(a->a.itemId()==published).findFirst().orElseThrow().reasons().get(0).contains("agent:b"));
            assertFalse(b.entries().stream().filter(a->a.itemId()==project).findFirst().orElseThrow().editableByAgent());
            var a=f.controller.visibility("a","p","s",null);
            assertTrue(a.entries().stream().anyMatch(e->e.itemId()==privateId && e.editableByAgent()));
            assertTrue(a.entries().stream().anyMatch(e->e.itemId()==bound));
            assertFalse(f.controller.visibility("a","q","other",null).entries().stream().anyMatch(e->e.itemId()==privateId));
            assertEquals(watermark,f.snapshots.watermark());
            assertTrue(f.snapshots.recent(null,null,null,100).isEmpty());
            assertThrows(ResponseStatusException.class,()->f.controller.visibility(" ",null,null,null));
        }
    }
    @Test void snapshotIsFrozenAndLegacySnapshotNeverUsesCurrentContent() throws Exception {
        try(var f=new Fixture()) {
            long id=f.item("AGENT","L2",null,"a",null);
            f.snapshot("task:1","a","p",null,id);
            f.items.updateContentCas(id,"changed","新正文",0);
            var detail=f.controller.detail("task:1");
            assertEquals("旧正文",detail.content().path("items").get(0).path("body").asText());
            assertEquals(List.of(id),detail.summary().itemIds());
            assertTrue(detail.requests().isEmpty());
            f.jdbc.update("INSERT INTO memory_snapshot(turn_id,session_id,agent_id,project_id,watermark,item_ids,tokens,built_at) VALUES('legacy','','','','0',?,0,0)",String.valueOf(id));
            assertNull(f.controller.detail("legacy").content());
            assertFalse(f.controller.detail("legacy").summary().complete());
            assertEquals(1,f.controller.recent("builtin",null,null,100).size());
            assertEquals(1,f.controller.recent("a","p",null,100).size());
            assertTrue(f.controller.recent("b",null,null,100).isEmpty());
            assertThrows(ResponseStatusException.class,()->f.controller.detail("missing"));
        }
    }
    @Test void actualRequestsDistinguishMissingMemoryFromMissingEvidence() throws Exception {
        try(var f=new Fixture()) {
            long id=f.item("USER","L1",null,null,null);
            f.snapshot("7","a",null,"s",id);
            String request=f.json.writeValueAsString(java.util.Map.of("messages",List.of(java.util.Map.of("role","system","content","other\n<minis-memory>实际发送的新内容</minis-memory>\nsecret outside memory"))));
            String without=f.json.writeValueAsString(java.util.Map.of("messages",List.of(java.util.Map.of("role","system","content","no memory"))));
            String calls=f.json.writeValueAsString(List.of(java.util.Map.of("round",0,"request",request,"response","{}"),java.util.Map.of("round",1,"request",without),java.util.Map.of("round",2,"request","broken"),java.util.Map.of("round",3,"request","null")));
            f.jdbc.update("INSERT INTO hub_session(id,created_at) VALUES('s',0)");
            f.jdbc.update("INSERT INTO chat_message(id,session_id,role,content,context,created_at) VALUES(10,'s','assistant','answer',?,0)",calls);
            f.jdbc.update("INSERT INTO chat_turn(id,session_id,trigger_source,reply_message_id,created_at,updated_at) VALUES(7,'s','USER_INPUT',10,0,0)");
            var r=f.controller.detail("7").requests();
            assertEquals(4,r.size()); assertEquals("实际发送的新内容",r.get(0).memoryText());
            assertTrue(r.get(0).responseRecorded());
            assertTrue(r.get(1).requestRecorded()); assertFalse(r.get(1).memorySectionPresent());
            assertFalse(r.get(2).requestRecorded()); assertFalse(r.get(3).requestRecorded());
            assertFalse(f.json.writeValueAsString(r).contains("secret outside"));
        }
    }
    @Test void workObservationShowsClosedHistoryButAgentUsesOnlyLiveContext() throws Exception {
        try(var f=new Fixture()) {
            long live=f.works.add("s",null,"a","p","progress",null,"当前工作",null,null,null);
            long closed=f.works.add("s",null,"a","p","progress",null,"已关闭",null,null,null); f.works.setStatus(closed,"closed");
            f.works.add("s",null,"a","other","progress",null,"其他项目",null,null,null);
            f.works.add("s",null,"a","p","progress",null,"过期",null,null,1L);
            assertEquals(4,f.controller.observedWork(200).size());
            assertEquals(List.of(live),f.controller.visibility("a","p","s",null).work().stream().map(MemoryController.WorkingView::id).toList());
            assertTrue(f.controller.visibility("a","p",null,null).work().isEmpty());
        }
    }
}
