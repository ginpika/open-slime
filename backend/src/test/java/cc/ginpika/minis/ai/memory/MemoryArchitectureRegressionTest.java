package cc.ginpika.minis.ai.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import cc.ginpika.minis.ai.core.*;
import cc.ginpika.minis.ai.llm.EmbeddingClient;
import cc.ginpika.minis.ai.memory.recall.MemoryItemRecall;
import cc.ginpika.minis.ai.memory.store.*;
import cc.ginpika.minis.config.MemoryProps;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** 用实际 schema 和 SQLite 验证架构边界，而非仅对照策略函数自身。 */
class MemoryArchitectureRegressionTest {
    static final class Fixture implements AutoCloseable {
        final SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        final JdbcTemplate jdbc = new JdbcTemplate(ds);
        final MemoryRevisionStore revisions;
        final MemoryItemStore items;
        final MemoryPublisher publisher;
        final MemoryItemEmbeddingStore vectors;
        Fixture() throws Exception {
            ScriptUtils.executeSqlScript(ds.getConnection(), new ClassPathResource("schema.sql"));
            revisions = new MemoryRevisionStore(jdbc);
            items = new MemoryItemStore(jdbc, revisions);
            publisher = new MemoryPublisher(new MemoryShareStore(jdbc), new MemoryHandoffStore(jdbc),
                    new MemoryPromotionStore(jdbc), items);
            vectors = new MemoryItemEmbeddingStore(jdbc);
        }
        long item(String owner, String layer, String project, String agent, String session,
                  String scope, String body, String retention) {
            return items.insert(new MemoryItemStore.Ownership(null, owner,
                            owner.equals("USER") ? "USER_PROFILE" : owner.equals("TASK") ? "TASK_EPISODIC" : owner,
                            layer, project, session, null, agent),
                    new MemoryItemStore.Governance(agent, "real", "explicit", retention, MemoryIdentity.REAL),
                    scope, body, body, 0.9, "manual", null,
                    MemoryEvidence.ref("chat_message", 1, body));
        }
        public void close() { ds.destroy(); MemoryLiveContext.clear(); MemoryLiveContext.takeCaptured(); }
    }
    static MemoryExec exec(String agent, String project, String session) {
        return MemoryExec.chat(agent, project, session, 10L, "turn", MemoryIdentity.REAL);
    }

    @Test void projectLayersDoNotBypassIsolationOrPublication() throws Exception {
        try (var f = new Fixture()) {
            f.item("PROJECT", "L2", "p", "a", "s1", "method", "私有方法", "normal");
            f.item("PROJECT", "L3", "p", "a", "s1", "fact", "临时事实", "normal");
            long l4 = f.item("PROJECT", "L4", "p", "a", "s1", "knowledge", "交接", "long");
            assertEquals(0, f.items.visible(exec("b", "p", "s2"), null, 50).size());
            long share = f.publisher.publish(l4, "agent", "b", null);
            assertEquals(List.of(l4), f.items.visible(exec("b", "p", "s2"), null, 50).stream().map(MemoryItemStore.ItemRow::id).toList());
            f.publisher.revoke(share);
            assertTrue(f.items.visible(exec("b", "p", "s2"), null, 50).isEmpty());
        }
    }
    @Test void eventsAreBoundToSessionAndLegacyEventsAreHumanOnly() throws Exception {
        try (var f = new Fixture()) {
            var events = new MemoryEventStore(f.jdbc);
            events.append(exec("a", "p", "s1"), "one", "chat", "user", null, "秘密", "chat_message", 1);
            events.append("legacy", "chat", "user", null, "历史", "chat_message", 2);
            assertTrue(events.window(MemoryExec.chat("b", "other", "s2", null, false), null, null, null, null, List.of(), 10).isEmpty());
            assertEquals(1, events.window(exec("a", "p", "s1"), null, null, null, null, List.of(), 10).size());
            assertEquals(2, events.recent(10).size());
            assertTrue(events.window(MemoryExec.none(), null, null, null, null, List.of(), 10).isEmpty());
        }
    }
    @Test void protectedEntriesSurviveCandidateFloodAndMissingEmbedding() throws Exception {
        try (var f = new Fixture()) {
            long profile = f.item("USER", "L1", null, null, null, "profile", "使用 Java", "long");
            long rule = f.item("USER", "L1", null, null, null, "directive", "不能覆盖已确认决策", "long");
            for (int i = 0; i < 230; i++) { f.item("PROJECT", "L3", "p", "a", "s1", "episode", "剧情" + i, "normal"); }
            var embedding = mock(EmbeddingClient.class);
            var recall = new MemoryItemRecall(f.items, f.vectors, embedding, new MemoryProps());
            var result = recall.recall(exec("a", "p", "s1"), "查询", null, 1);
            assertEquals(java.util.Set.of(profile, rule), result.stream().map(h -> h.item().id()).collect(java.util.stream.Collectors.toSet()));
            assertEquals(2, recall.visible(exec("a", "p", "s1"), 1).size());
        }
    }
    @Test void episodeRetentionIsIndependentAndLongRetentionIsRespected() throws Exception {
        try (var f = new Fixture()) {
            long other = f.item("AGENT", "L2", null, "b", null, "episode", "重要旧事件", "normal");
            long permanent = f.item("AGENT", "L2", null, "a", null, "episode", "长期保留", "long");
            for (int i = 0; i < 5; i++) { f.item("PROJECT", "L3", "novel", "a", "s1", "episode", "小说" + i, "normal"); }
            var props = new MemoryProps(); props.setEpisodeMax(2);
            assertEquals(3, new MemoryConsolidator(props, f.items).consolidate());
            assertEquals("active", f.items.findById(other).orElseThrow().status());
            assertEquals("active", f.items.findById(permanent).orElseThrow().status());
        }
    }
    @Test void identicalTaskBodiesHaveIndependentFingerprints() throws Exception {
        try (var f = new Fixture()) {
            long a = f.item("TASK", "L3", null, "a", "s1", "fact", "相同内容", "normal");
            long b = f.item("TASK", "L3", null, "a", "s2", "fact", "相同内容", "normal");
            assertNotEquals(f.items.findById(a).orElseThrow().fingerprint(), f.items.findById(b).orElseThrow().fingerprint());
            assertEquals(0, new MemoryConsolidator(new MemoryProps(), f.items).consolidate());
        }
    }
    @Test void updateInvalidatesVectorAndRejectsOldAsyncEmbedding() throws Exception {
        try (var f = new Fixture()) {
            long id = f.item("AGENT", "L2", null, "a", null, "method", "旧内容", "normal");
            String oldFp = f.items.findById(id).orElseThrow().fingerprint();
            assertTrue(f.vectors.saveVersion(id, "m", new float[]{1,0}, 0));
            assertEquals(1, f.items.updateContentCas(id, "new", "新内容", 0));
            assertNotEquals(oldFp, f.items.findById(id).orElseThrow().fingerprint());
            assertTrue(f.vectors.byItemIds("m", List.of(id)).isEmpty());
            assertFalse(f.vectors.saveVersion(id, "m", new float[]{1,0}, 0));
            assertEquals(id, f.vectors.itemsMissing("m", 10).get(0).id());
            assertTrue(f.revisions.byItem(id, 10).get(0).beforeJson().contains("旧内容"));
        }
    }
    @Test void profileCandidateCarriesIndependentEvidenceAndExpiry() throws Exception {
        try (var f = new Fixture()) {
            var candidates = new MemoryCandidateStore(f.jdbc);
            var revocations = new MemoryRevocationStore(f.jdbc);
            var profiles = new UserProfileService(candidates, revocations, f.items, mock(MemoryItemEmbeddingWriter.class));
            long expiry = System.currentTimeMillis() + 100000;
            String refs = MemoryEvidence.ref("chat_message", 1, "我使用 Java");
            long id = profiles.submit(exec("a", null, "s1"), "profile", null, "使用 Java", "explicit", refs, expiry);
            profiles.submit(exec("a", null, "s1"), "profile", null, "使用 Java", "explicit", refs, expiry);
            assertEquals(1, candidates.findById(id).orElseThrow().evidenceCount());
            long approved = profiles.approve(id);
            assertEquals(expiry, f.items.findById(approved).orElseThrow().validUntil());
            var fiction = new MemoryExec(null,"a","novel","s1",null,MemorySurface.CHAT,false,null,MemoryIdentity.FICTION);
            assertNull(profiles.submit(fiction, "profile", null, "角色偏好", "explicit", refs));
            assertEquals("L2", MemoryExtractor.ownershipFor(exec("a", "p", "s1"), "method").layer());
            assertEquals("L3", MemoryExtractor.ownershipFor(exec("a", "p", "s1"), "fact").layer());
        }
    }
    @Test void sharedAuthorityAndOtherSessionCannotBeEditedByAgent() throws Exception {
        try (var f = new Fixture()) {
            for (String layer : List.of("L1", "L3", "L4")) {
                long id = f.item("PROJECT", layer, "p", "a", "s1", "fact", "内容" + layer, "normal");
                assertFalse(MemoryEditService.canEdit(exec("a", "p", "s2"), f.items.findById(id).orElseThrow()));
            }
            long temporary = f.item("PROJECT", "L3", "p", "a", "s1", "fact", "待确认", "normal");
            assertEquals(1, f.publisher.confirmProject(temporary, 0));
            assertEquals("L1", f.items.findById(temporary).orElseThrow().layer());
        }
    }
    @Test void revokedHandoffsDisappearAndTransitionsAreAuthorized() throws Exception {
        try (var f = new Fixture()) {
            long id = f.item("AGENT", "L2", null, "a", null, "method", "交接内容", "normal");
            var handoff = f.publisher.handoff(id, "agent", "b", null);
            assertThrows(IllegalArgumentException.class, () -> f.publisher.move(exec("c",null,"s"), handoff[1], "accepted"));
            assertThrows(IllegalStateException.class, () -> f.publisher.move(exec("b",null,"s"), handoff[1], "done"));
            f.publisher.revoke(handoff[0]);
            assertTrue(f.publisher.inbox(exec("b",null,"s"),10).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> f.publisher.move(exec("b",null,"s"), handoff[1], "accepted"));
        }
    }
    @Test void forgettingBlocksRelearningAndInvalidPromotionIsRejected() throws Exception {
        try (var f = new Fixture()) {
            long id = f.item("AGENT", "L2", null, "a", null, "method", "应忘掉", "normal");
            var edit = new MemoryEditService(f.items, mock(MemoryItemRecall.class), new MemoryRevocationStore(f.jdbc), f.publisher);
            assertTrue(edit.forget(exec("a",null,"s1"),id,null,"用户要求").ok());
            assertTrue(f.items.relearnBlocked(f.items.findById(id).orElseThrow().fingerprint()));
            long active = f.item("AGENT", "L2", null, "a", null, "method", "私有经验", "normal");
            long promotion = f.publisher.promote(active,"L1",1,null);
            assertThrows(IllegalArgumentException.class, () -> f.publisher.applyPromotion(promotion));
            assertEquals("L2", f.items.findById(active).orElseThrow().layer());
        }
    }
    @Test void immutableReplayAndLiveRevocationKeepTheirDifferentSemantics() throws Exception {
        try (var f = new Fixture()) {
            long id = f.item("USER","L1",null,null,null,"directive","旧规则","long");
            var scope = exec("a",null,"s1");
            var snapshot = new MemorySnapshot("turn",scope,1,List.of(f.items.findById(id).orElseThrow()),List.of(),System.currentTimeMillis(),1000);
            var snapshots = new MemorySnapshotStore(f.jdbc); snapshots.save(snapshot,10);
            f.items.updateContentCas(id,null,"新规则",0);
            assertTrue(snapshots.find("turn").orElseThrow().contentJson().contains("旧规则"));
            var om = new ObjectMapper(); var messages = om.createArrayNode();
            messages.addObject().put("role","system").put("content",MemoryLiveContext.START+"旧规则"+MemoryLiveContext.END)
                    .set("_minis_memory",om.valueToTree(snapshot));
            MemoryLiveContext.begin(messages);
            var live = new MemoryLiveContext(f.items,new MemoryWorkStore(f.jdbc));
            MemoryScopeCtx.runVoid(scope, () -> live.refresh(messages));
            assertTrue(messages.toString().contains("新规则"));
            f.items.setStatus(id,"revoked");
            MemoryScopeCtx.runVoid(scope, () -> live.refresh(messages));
            assertFalse(messages.toString().contains("新规则"));
            assertFalse(messages.toString().contains("_minis_memory"));
        }
    }
    @Test void failedDistillationLeavesWorkAvailableForRetry() throws Exception {
        try (var f = new Fixture()) {
            var ingest = mock(MemoryIngest.class);
            when(ingest.distill(any(),anyString(),anyString())).thenReturn(CompletableFuture.completedFuture(false));
            var service = new WorkingSetService(new MemoryWorkStore(f.jdbc),ingest,new MemoryProps());
            var scope = exec("a","p","s1");
            long id = service.record(scope,"temp_conclusion",null,"待验证猜测",null,null);
            assertEquals(1, service.distill(scope));
            assertEquals(id, service.open(scope,10).get(0).id());
            assertTrue(service.open(null,10).isEmpty());
        }
    }

    @Test void lateExtractionAfterSessionCloseCannotReopenTaskMemory() throws Exception {
        try (var f = new Fixture()) {
            var scope = exec("a", "p", "s1");
            String refs = MemoryEvidence.ref("chat_message", 1, "收尾前的内容");
            f.items.closeSession(scope);
            long late = MemoryScopeCtx.run(scope, () -> f.items.insert(
                    MemoryExtractor.ownershipFor(scope, "fact"),
                    new MemoryItemStore.Governance("a","inferred","inferred","normal",MemoryIdentity.REAL),
                    "fact",null,"延迟写入",0.9,"auto",null,refs));
            assertEquals("archived", f.items.findById(late).orElseThrow().status());
        }
    }

    @Test void publicationRollsBackBothTablesWhenHandoffCreationFails() throws Exception {
        try (var f = new Fixture()) {
            long id = f.item("AGENT","L2",null,"a",null,"method","事务交接","normal");
            f.jdbc.execute("CREATE TRIGGER fail_handoff BEFORE INSERT ON memory_handoff BEGIN SELECT RAISE(ABORT,'test failure'); END");
            var proxy = new org.springframework.aop.framework.ProxyFactory(f.publisher);
            proxy.setProxyTargetClass(true);
            proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(f.ds),
                    new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
            var service = (MemoryPublisher) proxy.getProxy();
            assertThrows(RuntimeException.class, () -> service.handoff(id,"agent","b",null));
            assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM memory_share",Integer.class));
            assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM memory_handoff",Integer.class));
        }
    }

    @Test void extractionUsesRealUserQuoteAndHonorsTombstones() throws Exception {
        try (var f = new Fixture()) {
            var settings = mock(cc.ginpika.minis.config.SettingsService.class);
            when(settings.chatModel()).thenReturn(new cc.ginpika.minis.config.ModelTarget("local","test","stub"));
            var client = mock(cc.ginpika.minis.ai.llm.OpenAiChatClient.class);
            String output = "[{\"scope\":\"profile\",\"body\":\"使用Java\",\"confidence\":0.9,\"evidence_quote\":\"我使用Java\"},"
                    + "{\"scope\":\"method\",\"body\":\"应忘掉的方法\",\"confidence\":0.9}]";
            when(client.chat(any(),anyList(),anyString())).thenReturn(
                    new cc.ginpika.minis.ai.llm.OpenAiChatClient.Completion(output,null,null));
            var candidates = new MemoryCandidateStore(f.jdbc);
            var revocations = new MemoryRevocationStore(f.jdbc);
            var vectors = mock(MemoryItemEmbeddingWriter.class);
            var profiles = new UserProfileService(candidates, revocations, f.items, vectors);
            var extractor = new MemoryExtractor(new MemoryProps(),settings,client,new ObjectMapper(),f.items,vectors,profiles,revocations);
            var scope = exec("a",null,"s1");
            revocations.add(null,MemoryVocabulary.fingerprint("AGENT",null,"a","AGENT","method","应忘掉的方法"),"忘掉",false);
            assertTrue(extractor.extractConversationAsync(scope,"我使用Java",true,MemoryEvidence.ref("chat_message",1,"我使用Java")).join());
            var candidate = candidates.listByStatus("pending").get(0);
            assertEquals("explicit",candidate.evidenceLevel());
            assertEquals(0,f.items.listAll().size(),"已遗忘的方法没有被重建，画像尚未批准");
            assertEquals("USER",f.items.findById(profiles.approve(candidate.id())).orElseThrow().owner());
        }
    }

    @Test void completingChildTaskDoesNotArchiveItsParentSession() throws Exception {
        try (var f = new Fixture()) {
            long one = f.items.insert(new MemoryItemStore.Ownership(null,"PROJECT","PROJECT","L3","p","s",1L,"a"),
                    new MemoryItemStore.Governance("a","real","explicit","normal",MemoryIdentity.REAL),"fact",null,"子任务",0.9,"manual",null,null);
            long two = f.items.insert(new MemoryItemStore.Ownership(null,"PROJECT","PROJECT","L3","p","s",2L,"a"),
                    new MemoryItemStore.Governance("a","real","explicit","normal",MemoryIdentity.REAL),"fact",null,"父任务",0.9,"manual",null,null);
            f.items.closeTask(new MemoryExec(null,"a","p",null,1L,MemorySurface.SUBAGENT,false,null,MemoryIdentity.REAL));
            assertEquals("archived",f.items.findById(one).orElseThrow().status());
            assertEquals("active",f.items.findById(two).orElseThrow().status());
        }
    }

    @Test void subagentAndParallelToolsInheritFictionIdentity() throws Exception {
        var parent = new MemoryExec(null,"a","novel","s",1L,MemorySurface.CHAT,false,"t",MemoryIdentity.FICTION);
        var service = new cc.ginpika.minis.agent.subagent.SubagentService(null,null,null,null,null,null,null,null,null,null);
        var method = service.getClass().getDeclaredMethod("memScope",String.class,String.class);
        method.setAccessible(true);
        MemoryScopeCtx.runVoid(parent, () -> {
            try {
                var child = (MemoryExec) method.invoke(service,"a","s");
                assertEquals("novel",child.projectId());
                assertEquals(MemoryIdentity.FICTION,child.identity());
                var om = new ObjectMapper();
                var call = om.createObjectNode(); call.putObject("function").put("name","test");
                var result = cc.ginpika.minis.agent.runtime.ToolBatch.run(List.of(call,call), name -> true,
                        ignored -> MemoryScopeCtx.get().projectId()+":"+MemoryScopeCtx.get().identity());
                assertEquals(List.of("novel:FICTION","novel:FICTION"),result);
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        });
        assertNull(MemoryScopeCtx.get());
    }
}
