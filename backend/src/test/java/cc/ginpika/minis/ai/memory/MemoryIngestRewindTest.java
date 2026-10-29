package cc.ginpika.minis.ai.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.core.MemoryIdentity;
import cc.ginpika.minis.config.MemoryProps;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MemoryIngestRewindTest {
    @Test void pruningMixedBatchKeepsEarlierAndOtherSessionTurnsWithCorrectScope() throws Exception {
        var extractor = mock(MemoryExtractor.class);
        var props = new MemoryProps();
        props.setBatchTurns(1);
        var ds=new org.springframework.jdbc.datasource.SingleConnectionDataSource("jdbc:sqlite::memory:",true);
        org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(ds.getConnection(),new org.springframework.core.io.ClassPathResource("schema.sql"));
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(ds);
        when(extractor.extractConversationAsync(any(),anyString(),anyBoolean(),any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(true));
        var ingest = new MemoryIngest(props, extractor,new cc.ginpika.minis.ai.memory.store.MemoryIngestStore(jdbc,new cc.ginpika.minis.ai.memory.graph.MemoryGraphStore(jdbc)));
        var kept = MemoryExec.chat("a", null, "s", 10L, "1", MemoryIdentity.REAL);
        var removed = MemoryExec.chat("a", null, "s", 11L, "2", MemoryIdentity.REAL);
        var other = MemoryExec.chat("a", null, "other", 12L, "3", MemoryIdentity.REAL);
        ingest.tap("s", kept, "保留提问", "保留回复", MemoryEvidence.ref("chat_message", 1, "保留提问"));
        ingest.tap("s", removed, "丢弃提问", "丢弃回复", MemoryEvidence.ref("chat_message", 3, "丢弃提问"));
        ingest.tap("other", other, "其他会话", "其他回复", MemoryEvidence.ref("chat_message", 5, "其他会话"));
        ingest.rewind("s", Set.of(3L, 4L));
        var scan = MemoryIngest.class.getDeclaredMethod("scan");
        scan.setAccessible(true);
        scan.invoke(ingest);
        var text = ArgumentCaptor.forClass(String.class);
        var refs = ArgumentCaptor.forClass(String.class);
        verify(extractor, timeout(3000)).extractConversationAsync(eq(kept), text.capture(), eq(false), refs.capture());
        assertTrue(text.getValue().contains("保留提问"));
        assertFalse(text.getValue().contains("丢弃"));
        assertTrue(MemoryEvidence.references(refs.getValue()).contains("chat_message:1"));
        verify(extractor, timeout(3000)).extractConversationAsync(eq(other), contains("其他会话"), eq(false), anyString());
        verify(extractor, never()).extractConversationAsync(eq(removed), anyString(), anyBoolean(), any());
    }
}
