package cc.ginpika.minis.ai.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import cc.ginpika.minis.ai.core.*;
import cc.ginpika.minis.ai.knowledge.store.GraphStore;
import cc.ginpika.minis.ai.llm.EmbeddingClient;
import cc.ginpika.minis.ai.memory.graph.MemoryGraphStore;
import cc.ginpika.minis.ai.memory.recall.MemoryItemRecall;
import cc.ginpika.minis.ai.memory.store.*;
import cc.ginpika.minis.config.MemoryProps;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class UnifiedMemoryGraphTest {
    static final class Fixture implements AutoCloseable {
        final SingleConnectionDataSource ds=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);
        final JdbcTemplate jdbc=new JdbcTemplate(ds);
        final MemoryGraphStore graph=new MemoryGraphStore(jdbc);
        final MemoryItemStore items=graph.claims();
        final GraphStore view=new GraphStore(jdbc,new ObjectMapper());
        Fixture() throws Exception { ScriptUtils.executeSqlScript(ds.getConnection(),new ClassPathResource("schema.sql"));jdbc.execute("PRAGMA foreign_keys=ON"); }
        long item(String body,String project,String agent,String session,String identity) {
            return items.insert(new MemoryItemStore.Ownership(null,project==null?"AGENT":"PROJECT",project==null?"AGENT":"PROJECT","L2",project,session,null,agent),
                    new MemoryItemStore.Governance(agent,"inferred","inferred","long",MemoryIdentity.valueOf(identity)),"method",body,body,0.9,"auto",null,null);
        }
        public void close() { ds.destroy(); }
    }
    static MemoryExec exec(String agent,String project,String session) {return MemoryExec.chat(agent,project,session,null,"turn",MemoryIdentity.REAL);}

    @Test void oldestFactRemainsSearchableAmongOneHundredThousandNewerFacts() throws Exception {
        try(var f=new Fixture()) {
            long target=f.item("orbital-protocol","p","a",null,"REAL");
            f.jdbc.update("WITH RECURSIVE seq(n) AS (VALUES(1) UNION ALL SELECT n+1 FROM seq WHERE n<100000) "
                    +"INSERT INTO graph_claim(scope,body,title,valid_from,created_at,updated_at,owner,domain,layer,project_id,agent_id,status,version,identity) "
                    +"SELECT 'method','irrelevant payload '||n,'noise',1,1,1,'PROJECT','PROJECT','L2','p','a','active',0,'REAL' FROM seq");
            var vectors=new MemoryItemEmbeddingStore(f.jdbc);
            vectors.saveVersion(target,"m",new float[]{1,0},0);
            f.jdbc.update("INSERT INTO memory_item_embedding(item_id,model,dim,vec,updated_at,item_version) SELECT id,'m',2,?,1,0 FROM graph_claim WHERE id<>?",MemoryVecMath.encode(new float[]{0,1}),target);
            var embedding=mock(EmbeddingClient.class);when(embedding.configured()).thenReturn(true);when(embedding.model()).thenReturn("m");when(embedding.embed(anyString())).thenReturn(Optional.of(new float[]{1,0}));
            long start=System.nanoTime();
            var hits=new MemoryItemRecall(f.items,vectors,embedding,new MemoryProps()).recall(exec("a","p","s"),"old semantic fact",null,6);
            assertEquals(target,hits.getFirst().item().id());
            when(embedding.configured()).thenReturn(false);
            assertEquals(target,new MemoryItemRecall(f.items,vectors,embedding,new MemoryProps()).recall(exec("a","p","s"),"orbital-protocol",null,6).getFirst().item().id());
            System.out.println("graph-memory scale check: 100001 claims; semantic + lexical recall ms="+(System.nanoTime()-start)/1_000_000);
        }
    }

    @Test void graphTraversalAndRecallShareStatementStatusAndPermissions() throws Exception {
        try(var f=new Fixture()) {
            long id=f.item("database deployment","p","a",null,"REAL");
            f.items.attachTriples(id,new ObjectMapper().readTree("[{\"subject\":\"service\",\"predicate\":\"uses\",\"object\":\"PostgreSQL\"}]"));
            MemoryScopeCtx.runVoid(exec("a","p","s"),()->{
                var service=f.view.lookup(List.of("service"),10).getFirst();
                assertEquals(1,f.view.edgesTouching(List.of(service.id())).size());
                assertFalse(f.graph.related(exec("a","p","s"),List.of(id),10).isEmpty());
            });
            MemoryScopeCtx.runVoid(exec("b","other","s2"),()->assertTrue(f.view.lookup(List.of("service"),10).isEmpty()));
            f.items.setStatus(id,"revoked");
            MemoryScopeCtx.runVoid(exec("a","p","s"),()->assertTrue(f.view.lookup(List.of("service"),10).isEmpty()));
        }
    }

    @Test void namesAreScopedAndFictionDoesNotLeakIntoRealMemory() throws Exception {
        try(var f=new Fixture()) {
            MemoryScopeCtx.runVoid(exec("a","p","s1"),()->f.view.upsertEntity("PERSON","Alex",List.of(),"first person"));
            String first=MemoryScopeCtx.run(exec("a","p","s1"),()->f.view.byName("Alex").id());
            MemoryScopeCtx.runVoid(exec("a","q","s2"),()->f.view.upsertEntity("PERSON","Alex",List.of(),"other person"));
            String second=MemoryScopeCtx.run(exec("a","q","s2"),()->f.view.byName("Alex").id());
            assertNotEquals(first,second);
            long fictional=f.item("fictional secret","p","a",null,"FICTION");
            assertTrue(f.items.visibleById(exec("a","p","s1"),fictional).isEmpty());
        }
    }

    @Test void duplicateDocumentFactsAccumulateEvidenceAndSurviveOneSourceRemoval() throws Exception {
        try(var f=new Fixture()) {
            f.jdbc.update("INSERT INTO chunks(id,uuid,source,content,created_at) VALUES(1,'c1','test','first original source',1),(2,'c2','test','second original source',2)");
            f.view.upsertEntity("PROJECT","service",List.of(),null);f.view.upsertEntity("CONCEPT","database",List.of(),null);
            String s=f.view.byName("service").id(),o=f.view.byName("database").id();
            assertEquals(1,f.view.upsertRelation(s,"uses",o,1L));assertEquals(0,f.view.upsertRelation(s,"uses",o,2L));
            long claim=f.view.edgesTouching(List.of(s)).getFirst().claimId();
            assertEquals(2,f.graph.evidence(MemoryExec.system(null),claim,0,20).evidence().stream().filter(e->e.sourceRef().startsWith("chunk:")).count());
            f.view.deleteRelationsByChunk(1);assertEquals(1,f.view.edgesTouching(List.of(s)).size());
            f.view.deleteRelationsByChunk(2);assertTrue(f.view.edgesTouching(List.of(s)).isEmpty());
            assertEquals("revoked",f.items.findById(claim).orElseThrow().status());
        }
    }

    @Test void userAnchorCannotBypassProfileApproval() throws Exception {
        try(var f=new Fixture()) {
            f.view.upsertEntity("ANCHOR","用户",List.of(),null);f.view.upsertEntity("CONCEPT","coffee",List.of(),null);
            String user=f.view.byName("用户").id(),coffee=f.view.byName("coffee").id();
            MemoryScopeCtx.runVoid(exec("a",null,"s"),()->{
                assertThrows(IllegalArgumentException.class,()->f.view.upsertRelation(user,"likes",coffee,null));
                assertThrows(IllegalArgumentException.class,()->f.view.upsertEntity("ANCHOR","用户",List.of(),"unapproved preference"));
            });
        }
    }

    @Test void sharingAFactDoesNotShareTheFullPrivateConversation() throws Exception {
        try(var f=new Fixture()) {
            long claim=f.item("use connection pooling",null,"a",null,"REAL");
            String raw=f.graph.episode("chat_message:1","private unrelated details",exec("a",null,"s1"),1);
            f.graph.support(claim,raw);
            new MemoryShareStore(f.jdbc).publish(claim,"AGENT","a",null,"agent","b",null);
            var evidence=f.graph.evidence(exec("b",null,"s2"),claim,0,8).evidence().getFirst();
            assertFalse(evidence.readable());assertEquals("",evidence.payload());
            assertEquals("private unrelated details",f.graph.evidence(exec("a",null,"s1"),claim,0,8).evidence().getFirst().payload());
        }
    }

    @Test void durableJobsPreserveLongEndingsRetryAndFenceOldWorkers() throws Exception {
        try(var f=new Fixture()) {
            var jobs=new MemoryIngestStore(f.jdbc,f.graph);
            String text="prefix"+" x".repeat(14000)+"IMPORTANT ENDING";
            String group=jobs.enqueue(exec("a",null,"s"),text,MemoryEvidence.ref("chat_message",1,"source"),true,0);
            var first=jobs.claim(group,2);assertFalse(first.isEmpty());
            f.jdbc.update("UPDATE graph_ingest_job SET lease_until=0 WHERE state='running'");
            var restarted=new MemoryIngestStore(f.jdbc,f.graph);var recovered=restarted.claim(group,2);
            var gate=new MemoryRetractionStore(f.jdbc,f.items,new MemoryCandidateStore(f.jdbc));
            assertFalse(gate.current("[{\"ref\":\"graph_job:"+first.getFirst().id()+":"+first.getFirst().token()+"\"}]"));
            assertTrue(gate.current("[{\"ref\":\"graph_job:"+recovered.getFirst().id()+":"+recovered.getFirst().token()+"\"}]"));
            jobs.complete(first,true,null);
            assertEquals(0,f.jdbc.queryForObject("SELECT count(*) FROM graph_ingest_job WHERE state='done'",Integer.class));
            restarted.complete(recovered,false,"model unavailable");
            assertTrue(f.jdbc.queryForObject("SELECT count(*) FROM graph_ingest_job WHERE state='pending'",Integer.class)>0);
            assertEquals(text,f.jdbc.queryForObject("SELECT payload FROM graph_episode WHERE source_ref LIKE 'ingest:%'",String.class));
            assertTrue(f.jdbc.queryForObject("SELECT count(*) FROM graph_episode WHERE source_ref LIKE 'window:%' AND payload LIKE '%IMPORTANT ENDING'",Integer.class)>0);
        }
    }

    @Test void independentPreferencesCoexistAndRetractionReachesGraphChildren() throws Exception {
        try(var f=new Fixture()) {
            long source=f.item("source fact",null,"a",null,"REAL");
            var edges=new MemoryEdgeStore(f.jdbc);
            edges.add(null,"AGENT",null,"a","user","likes","Java",0.9,null,source);
            edges.add(null,"AGENT",null,"a","user","likes","Python",0.9,null,source);
            assertEquals(2,edges.activeBySubject("AGENT",null,"a","user").size());
            edges.invalidateBySourceItem(source);
            assertTrue(edges.activeBySubject("AGENT",null,"a","user").isEmpty());
        }
    }

    @Test void identicalPublicEndpointsHaveIndependentPrivateClaims() throws Exception {
        try(var f=new Fixture()) {
            f.view.upsertEntity("CONCEPT","public source",List.of(),null);f.view.upsertEntity("CONCEPT","public target",List.of(),null);
            String source=f.view.byName("public source").id(),target=f.view.byName("public target").id();
            MemoryScopeCtx.runVoid(exec("a","p","s1"),()->assertEquals(1,f.view.upsertRelation(source,"uses",target,null)));
            MemoryScopeCtx.runVoid(exec("b","q","s2"),()->{
                assertTrue(f.view.edgesTouching(List.of(source)).isEmpty());
                assertEquals(1,f.view.upsertRelation(source,"uses",target,null));
                assertEquals(1,f.view.edgesTouching(List.of(source)).size());
            });
            MemoryScopeCtx.runVoid(exec("a","p","s1"),()->assertEquals(1,f.view.edgesTouching(List.of(source)).size()));
        }
    }

    @Test void rewindRetractsNodeMetadataAndCannotLeaveASecondMemoryInGraphProperties() throws Exception {
        try(var f=new Fixture()) {
            var scope=MemoryExec.chat("a","p","s",7L,"turn",MemoryIdentity.REAL);
            MemoryScopeCtx.runVoid(scope,()->f.view.upsertEntity("PERSON","private person",List.of(),"private description"));
            MemoryScopeCtx.runVoid(scope,()->assertEquals("private description",f.view.byName("private person").summary()));
            var retraction=new MemoryRetractionStore(f.jdbc,f.items,new MemoryCandidateStore(f.jdbc));
            org.springframework.test.util.ReflectionTestUtils.setField(retraction,"graph",f.graph);
            retraction.retractChat("s",List.of(),List.of(7L),List.of("turn"),0);
            MemoryScopeCtx.runVoid(scope,()->assertNull(f.view.byName("private person")));
        }
    }

    @Test void sourcePlaceholdersResolveToOriginalTextWithoutChangingTheFact() throws Exception {
        try(var f=new Fixture()) {
            long claim=f.item("derived statement",null,"a",null,"REAL");
            f.items.attachEvidence(claim,MemoryEvidence.ref("task",17,null));
            f.graph.episode("task:17","original complete execution result",exec("a",null,"s"),System.currentTimeMillis());
            var evidence=f.graph.evidence(MemoryExec.system(null),claim,0,20).evidence();
            assertEquals(1,evidence.size());assertEquals("original complete execution result",evidence.getFirst().payload());
            assertEquals("derived statement",f.items.findById(claim).orElseThrow().body());
        }
    }

    @Test void resettingDerivedMemoryPreservesCanonicalProjectDocumentBodies() throws Exception {
        try(var f=new Fixture()) {
            f.jdbc.update("INSERT INTO project(id,name,created_at,updated_at) VALUES('p','project',1,1)");
            var documents=new cc.ginpika.minis.project.ProjectKnowledgeStore(f.jdbc);
            documents.save("p","overview",new cc.ginpika.minis.project.ProjectKnowledgeStore.Write("overview","current state","durable project body",0,false),null,"owner");
            f.item("discard derived memory",null,"a",null,"REAL");
            f.items.clearAll();
            assertEquals("durable project body",documents.read("p","overview",null).content());
            assertEquals(1,f.jdbc.queryForObject("SELECT count(*) FROM graph_claim",Integer.class));
            assertTrue(f.jdbc.queryForList("PRAGMA foreign_key_check").isEmpty());
        }
    }

    @Test void homonymsInsideOneNamespaceRequireExplicitIdentityInsteadOfMerging() throws Exception {
        try(var f=new Fixture()) {
            String first=f.view.upsertEntity("PERSON",List.of(),"Alex",List.of(),"first Alex","person:1");
            String second=f.view.upsertEntity("PERSON",List.of(),"Alex",List.of(),"second Alex","person:2");
            assertNotEquals(first,second);assertEquals(2,f.view.lookup(List.of("Alex"),10).size());
            assertThrows(IllegalArgumentException.class,()->f.view.byName("Alex"));
            assertThrows(IllegalArgumentException.class,()->f.view.upsertEntity("PERSON","Alex",List.of(),"ambiguous update"));
            assertEquals("first Alex",f.view.byName(first).summary());
            assertEquals(first,f.view.upsertEntity("PERSON",List.of(),"Alex",List.of(),"first Alex updated","person:1"));
            assertEquals("second Alex",f.view.byName(second).summary());
        }
    }

    @Test void sharingARelationDoesNotLeakPrivateAliasesThroughSearch() throws Exception {
        try(var f=new Fixture()) {
            var owner=exec("a","p","s1");var receiver=exec("b","p","s2");
            String source=MemoryScopeCtx.run(owner,()->{
                f.view.upsertEntity("PERSON","Alex",List.of("secret-nickname"),"private summary");
                f.view.upsertEntity("CONCEPT","database",List.of(),null);
                String id=f.view.byName("Alex").id();f.view.upsertRelation(id,"uses",f.view.byName("database").id(),null);return id;
            });
            long claim=MemoryScopeCtx.run(owner,()->f.view.edgesTouching(List.of(source)).getFirst().claimId());
            new MemoryShareStore(f.jdbc).publish(claim,"PROJECT","a","p","agent","b",null);
            MemoryScopeCtx.runVoid(receiver,()->{
                assertTrue(f.view.lookup(List.of("secret-nickname"),10).isEmpty());
                assertNull(f.view.byName(source).aliases());assertNull(f.view.byName(source).summary());
                assertEquals(1,f.view.edgesTouching(List.of(source)).size());
            });
        }
    }

    @Test void quarantinedLegacyKnowledgeHasAnExplicitVersionedReviewPath() throws Exception {
        try(var f=new Fixture()) {
            f.view.upsertEntity("CONCEPT","source",List.of(),null);f.view.upsertEntity("CONCEPT","target",List.of(),null);
            String source=f.view.byName("source").id(),target=f.view.byName("target").id();f.view.upsertRelation(source,"uses",target,null);
            long claim=f.view.edgesTouching(List.of(source)).getFirst().claimId();f.items.markConflicted(claim);
            assertTrue(f.view.edgesTouching(List.of(source)).isEmpty());
            f.graph.reviewLegacy(claim,0,true,"已人工核对原始资料");
            assertEquals(1,f.view.edgesTouching(List.of(source)).size());
            assertThrows(IllegalArgumentException.class,()->f.graph.reviewLegacy(claim,0,true,"重复提交"));
            assertTrue(f.graph.evidence(MemoryExec.system(null),claim,0,20).evidence().stream().anyMatch(e->e.sourceRef().startsWith("human_review:")));
        }
    }
}
