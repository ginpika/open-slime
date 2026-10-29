package cc.ginpika.minis.coding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import cc.ginpika.minis.ai.core.*;
import cc.ginpika.minis.ai.memory.*;
import cc.ginpika.minis.ai.memory.graph.MemoryGraphStore;
import cc.ginpika.minis.ai.memory.recall.MemoryItemRecall;
import cc.ginpika.minis.ai.memory.store.*;
import cc.ginpika.minis.ai.llm.EmbeddingClient;
import cc.ginpika.minis.coding.api.CodingDtos.*;
import cc.ginpika.minis.coding.integration.CodingMemory;
import cc.ginpika.minis.config.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ClassPathResource;
import java.util.List;

class CodingMemoryTest {
    @Test void codingReadsOnlyItsRepositoryMemoryAndRechecksRevocationEachRound() throws Exception {
        var ds=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);
        try {
            ScriptUtils.executeSqlScript(ds.getConnection(),new ClassPathResource("schema.sql"));var jdbc=new JdbcTemplate(ds);
            var graph=new MemoryGraphStore(jdbc);var items=graph.claims();var props=new MemoryProps();
            long id=items.insert(new MemoryItemStore.Ownership(null,"PROJECT","PROJECT","L2","coding-repository:r",null,null,BuiltinAgents.CODING),
                    new MemoryItemStore.Governance(BuiltinAgents.CODING,"inferred","inferred","long",MemoryIdentity.REAL),"method","pooling","pooling evidence",0.9,"auto",null,null);
            items.insert(new MemoryItemStore.Ownership(null,"PROJECT","PROJECT","L2","coding-repository:other",null,null,BuiltinAgents.CODING),
                    new MemoryItemStore.Governance(BuiltinAgents.CODING,"inferred","inferred","long",MemoryIdentity.REAL),"method","pooling","pooling secret-other",0.9,"auto",null,null);
            var embedding=mock(EmbeddingClient.class);var work=mock(WorkingSetService.class);when(work.open(any(),anyInt())).thenReturn(List.of());
            var snapshots=new MemorySnapshotBuilder(new MemoryItemRecall(items,new MemoryItemEmbeddingStore(jdbc),embedding,props),new MemorySnapshotStore(jdbc),work,props);
            var ingest=mock(MemoryIngest.class);var json=new ObjectMapper();var memory=new CodingMemory(snapshots,ingest,props,new ContextProps(),json);
            Run run=mock(Run.class);when(run.id()).thenReturn("run");when(run.prompt()).thenReturn("pooling");when(run.attempt()).thenReturn(1);when(run.taskId()).thenReturn(7L);
            Repository repo=mock(Repository.class);when(repo.id()).thenReturn("r");
            var messages=json.createArrayNode();messages.addObject().put("role","system").put("content","coding rules");
            memory.refresh(run,repo,messages,1);
            assertTrue(messages.get(0).path("content").asText().contains("pooling evidence"));
            assertFalse(messages.get(0).path("content").asText().contains("secret-other"));
            items.setStatus(id,"revoked");memory.refresh(run,repo,messages,2);
            assertFalse(messages.get(0).path("content").asText().contains("pooling evidence"));
            assertTrue(messages.get(0).path("content").asText().contains("coding rules"));
            memory.completed(run,repo,"a proposed result");
            verify(ingest).tapExec(argThat(e->"coding-repository:r".equals(e.projectId()) && e.agentOrBuiltin().equals(BuiltinAgents.CODING)),contains("待验收"),eq("a proposed result"));
        } finally {ds.destroy();}
    }
}
