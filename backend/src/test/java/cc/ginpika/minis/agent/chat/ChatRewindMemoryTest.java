package cc.ginpika.minis.agent.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cc.ginpika.minis.agent.chat.store.*;
import cc.ginpika.minis.agent.chat.turn.*;
import cc.ginpika.minis.agent.runtime.*;
import cc.ginpika.minis.ai.core.*;
import cc.ginpika.minis.ai.llm.OpenAiChatClient;
import cc.ginpika.minis.ai.memory.*;
import cc.ginpika.minis.ai.memory.store.*;
import cc.ginpika.minis.config.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.web.server.ResponseStatusException;

class ChatRewindMemoryTest {
    static final class Fixture implements AutoCloseable {
        final SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        final JdbcTemplate jdbc = new JdbcTemplate(ds);
        final ChatMessageStore messages = new ChatMessageStore(jdbc);
        final HubSessionStore sessions = new HubSessionStore(jdbc);
        final TurnStore turns = new TurnStore(jdbc);
        final TaskStore tasks = new TaskStore(jdbc);
        final MemoryItemStore items = new MemoryItemStore(jdbc, new MemoryRevisionStore(jdbc));
        final MemoryCandidateStore candidates = new MemoryCandidateStore(jdbc);
        final MemoryEventStore events = new MemoryEventStore(jdbc);
        final MemoryWorkStore works = new MemoryWorkStore(jdbc);
        final ChatLiveStore live = new ChatLiveStore();
        final OpenAiChatClient client = mock(OpenAiChatClient.class);
        final MemoryRetractionStore retractions;
        final MemoryExtractor extractor;
        final MemoryIngest ingest;
        final ChatSessionService service;

        Fixture() throws Exception {
            ScriptUtils.executeSqlScript(ds.getConnection(), new ClassPathResource("schema.sql"));
            jdbc.execute("PRAGMA foreign_keys=ON");
            sessions.insert("s", "测试");
            sessions.insert("other", "其他会话");
            retractions = transactional(new MemoryRetractionStore(jdbc, items, candidates));
            var revocations = new MemoryRevocationStore(jdbc);
            var vectors = mock(MemoryItemEmbeddingWriter.class);
            var settings = mock(SettingsService.class);
            when(settings.chatModel()).thenReturn(new ModelTarget("http://localhost", "key", "model"));
            extractor = new MemoryExtractor(new MemoryProps(), settings, client, new ObjectMapper(), items,
                    vectors, new UserProfileService(candidates, revocations, items, vectors), revocations, retractions);
            ingest = new MemoryIngest(new MemoryProps(), extractor,new cc.ginpika.minis.ai.memory.store.MemoryIngestStore(jdbc,new cc.ginpika.minis.ai.memory.graph.MemoryGraphStore(jdbc)));
            service = transactional(new ChatSessionService(sessions, messages, live, mock(TodoService.class),
                    new ObjectMapper(), turns, ingest, retractions, new TaskCheckpointService(tasks)));
        }

        @SuppressWarnings("unchecked")
        <T> T transactional(T target) {
            var proxy = new ProxyFactory(target);
            proxy.setProxyTargetClass(true);
            var interceptor = new TransactionInterceptor();
            interceptor.setTransactionManager(new DataSourceTransactionManager(ds));
            interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
            proxy.addAdvice(interceptor);
            return (T) proxy.getProxy();
        }

        long pair(String session, String text) {
            long task = tasks.insert("session", null, text, session, "builtin", text);
            long user = messages.insert(session, "user", text, null, null, null, null, null);
            long turn = turns.begin(session, TurnTrigger.Source.USER_INPUT, user, task);
            long reply = messages.insert(session, "assistant", "答：" + text, null, null, null, null, null);
            turns.complete(turn, reply);
            events.append(scope(session, task), "chat_message:" + user, "chat", "user", null, text, "chat_message", user);
            events.append(scope(session, task), "chat_message:" + reply, "chat", "assistant", null, text, "chat_message", reply);
            tasks.setCheckpoint(task, "旧工作状态");
            return user;
        }

        long task(long message) {
            return jdbc.queryForObject("SELECT task_id FROM chat_turn WHERE trigger_message_id=?", Long.class, message);
        }
        MemoryExec scope(String session, Long task) {
            return MemoryExec.chat("builtin", null, session, task, null, MemoryIdentity.REAL);
        }
        long item(String session, String body, String refs) {
            return items.insert(new MemoryItemStore.Ownership(null, "AGENT", "AGENT", "L2", null, session, null, "builtin"),
                    new MemoryItemStore.Governance("builtin", "inferred", "inferred", "normal", MemoryIdentity.REAL),
                    "method", body, body, 0.9, "auto", null, refs);
        }
        String refs(long message) { return MemoryEvidence.ref("chat_message", message, "原话"); }
        public void close() { ds.destroy(); }
    }

    @Test void rewindRetractsDerivedMemoryAndKeepsEarlierAndOtherSessionMaterial() throws Exception {
        try (var f = new Fixture()) {
            long kept = f.pair("s", "保留内容");
            long removed = f.pair("s", "丢弃内容");
            long other = f.pair("other", "其他内容");
            long keptItem = f.item("s", "保留记忆", f.refs(kept));
            long removedItem = f.item("s", "丢弃记忆", f.refs(removed));
            long otherItem = f.item("other", "其他记忆", f.refs(other));
            long mixed = f.item("s", "混合摘要", MemoryEvidence.merge(f.refs(kept), f.refs(removed)));
            long event = f.events.byKey("chat_message:" + removed).id();
            long legacy = f.item("s", "旧格式记忆", "[" + event + "]");
            new MemoryEventEmbeddingStore(f.jdbc).save(event, "test", new float[]{1, 0});
            long keptWork = f.works.add("s", f.task(kept), "builtin", null, "progress", null, "保留工作", null, null, null);
            long lostWork = f.works.add("s", f.task(removed), "builtin", null, "progress", null, "丢弃工作", null, null, null);
            long distilled = f.item("s", "工作集蒸馏", MemoryEvidence.ref("memory_work", lostWork, "丢弃工作"));
            long candidate = f.candidates.upsertPending("candidate", null, "profile", null, "丢弃画像", "real", "explicit", f.refs(removed));
            new MemoryShareStore(f.jdbc).publish(removedItem, "AGENT", "builtin", null, "agent", "builtin", null);

            assertEquals(2, f.service.rewind("s", removed, null).removed());
            assertEquals(List.of(kept, kept + 1), f.messages.listBySession("s").stream().map(ChatMessageStore.MessageRow::id).toList());
            for (long id : List.of(removedItem, mixed, legacy, distilled)) {
                assertEquals("revoked", f.items.findById(id).orElseThrow().status());
            }
            assertEquals("active", f.items.findById(keptItem).orElseThrow().status());
            assertEquals("active", f.items.findById(otherItem).orElseThrow().status());
            assertEquals("open", f.works.findById(keptWork).orElseThrow().status());
            assertEquals("archived", f.works.findById(lostWork).orElseThrow().status());
            assertEquals("rejected", f.candidates.findById(candidate).orElseThrow().status());
            assertNull(f.tasks.checkpointOf(f.task(removed)));
            assertNotNull(f.tasks.checkpointOf(f.task(kept)));
            assertNull(f.events.byKey("chat_message:" + removed));
            assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM memory_event_embedding WHERE event_id=?", Integer.class, event));
            assertEquals(List.of(keptItem, otherItem).stream().sorted().toList(),
                    f.items.visible(f.scope("s", null), null, 100).stream().map(MemoryItemStore.ItemRow::id).sorted().toList());
            assertEquals(1, new History(f.messages, f.turns, f.live, new ObjectMapper(), new ContextProps()).turns("s").size());
        }
    }

    @Test void extractionReturningAfterRewindCannotRestoreDiscardedMemoryAndResendCanLearn() throws Exception {
        try (var f = new Fixture()) {
            long removed = f.pair("s", "丢弃的习惯");
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            when(f.client.chat(any(), anyList(), anyString())).thenAnswer(call -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return new OpenAiChatClient.Completion("[{\"scope\":\"method\",\"body\":\"丢弃的习惯\",\"confidence\":0.9}]", null, null);
            });
            var pending = f.extractor.extractConversationAsync(f.scope("s", f.task(removed)), "丢弃的习惯", false, f.refs(removed));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            try { f.service.rewind("s", null, 0); }
            finally { release.countDown(); }
            assertFalse(pending.get(5, TimeUnit.SECONDS));
            assertTrue(f.items.listAll().isEmpty());
            long resent = f.pair("s", "丢弃的习惯");
            assertTrue(f.extractor.extractConversationAsync(f.scope("s", f.task(resent)), "丢弃的习惯", false, f.refs(resent)).get(5, TimeUnit.SECONDS));
            assertEquals(1, f.items.visible(f.scope("s", null), null, 100).size());
            assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM memory_revocation", Integer.class));
        }
    }

    @Test void failedRewindRollsBackMessagesAndMemoryTogether() throws Exception {
        try (var f = new Fixture()) {
            long removed = f.pair("s", "保留至操作成功");
            long item = f.item("s", "原记忆", f.refs(removed));
            f.jdbc.execute("CREATE TRIGGER prevent_delete BEFORE DELETE ON chat_message BEGIN SELECT RAISE(ABORT, 'test failure'); END");
            assertThrows(RuntimeException.class, () -> f.service.rewind("s", removed, null));
            assertEquals(2, f.messages.listBySession("s").size());
            assertEquals("active", f.items.findById(item).orElseThrow().status());
            assertTrue(f.retractions.current(f.refs(removed)));
            assertNotNull(f.events.byKey("chat_message:" + removed));
        }
    }

    @Test void memoryRewrittenByDiscardedTurnCannotLeakThroughItsOlderSource() throws Exception {
        try (var f = new Fixture()) {
            long kept = f.pair("s", "旧输入");
            long item = f.item("s", "旧方法", f.refs(kept));
            long removed = f.pair("s", "改成错误的方法");
            String turn = f.jdbc.queryForObject("SELECT id FROM chat_turn WHERE trigger_message_id=?", String.class, removed);
            var scope = MemoryExec.chat("builtin", null, "s", f.task(removed), turn, MemoryIdentity.REAL);
            MemoryScopeCtx.runVoid(scope, () -> f.items.updateContentCas(item, "错误方法", "丢弃轮次改写的正文", 0, "auto"));
            f.service.rewind("s", removed, null);
            assertTrue(f.items.visible(f.scope("s", null), null, 100).isEmpty());
            assertEquals("revoked", f.items.findById(item).orElseThrow().status());
        }
    }

    @Test void invalidPositionsAndRunningTurnCannotRewind() throws Exception {
        try (var f = new Fixture()) {
            long input = f.pair("s", "原对话");
            long other = f.pair("other", "其他对话");
            assertThrows(ResponseStatusException.class, () -> f.service.rewind("s", input + 1, null));
            assertThrows(ResponseStatusException.class, () -> f.service.rewind("s", other, null));
            f.live.start("s", input, "原对话");
            assertEquals(409, assertThrows(ResponseStatusException.class, () -> f.service.rewind("s", input, null)).getStatusCode().value());
            assertEquals(2, f.messages.listBySession("s").size());
        }
    }
}
