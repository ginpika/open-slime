package cc.ginpika.minis.config;

import cc.ginpika.minis.agent.member.*;
import cc.ginpika.minis.ai.core.BuiltinAgents;
import cc.ginpika.minis.ai.core.prompt.skill.CodingSkill;
import cc.ginpika.minis.ai.core.prompt.store.PromptStore;
import cc.ginpika.minis.coding.api.CodingDtos.*;
import cc.ginpika.minis.coding.store.CodingStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

class CodingAgentMergeTest {
    @Test void upgradeArchivesLegacyConfigMergesOwnershipAndPreservesCheckpoints() {
        var source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        try {
            new ResourceDatabasePopulator(new ClassPathResource("schema.sql"),
                    new ClassPathResource("coding/schema.sql")).execute(source);
            var jdbc = new JdbcTemplate(source);
            var agents = new AgentStore(jdbc); new AgentSeeder(agents);
            agents.insert(new AgentStore.AgentRow(BuiltinAgents.CODING, "old-coder", true,
                    null, "old-model", 1, null, null, null, 0, 0, null, null));
            var legacy = new AgentPromptStore(jdbc);
            legacy.insertVersion(new AgentPromptStore.VersionRow("old", BuiltinAgents.CODING,
                    "identity", "custom coding", "preserved-user-coding-rules", 0));
            legacy.saveBinding(BuiltinAgents.CODING, "identity", "old");
            var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            var coding = new CodingStore(jdbc, tx, new ObjectMapper());
            var repo = coding.register("alice", "project", "/tmp/project", null, null);
            var run = coding.create("alice", new StartRun(repo.id(), "task", "frozen-model", Mode.PLAN,
                    Runner.DOCKER, 10), 10, null, null, "frozen-instructions");
            jdbc.update("UPDATE task SET agent_id=?", BuiltinAgents.CODING);
            jdbc.update("UPDATE coding_run_agent SET agent_id=?", BuiltinAgents.CODING);
            jdbc.update("INSERT INTO hub_session(id,mode,agent_id,member_ids,created_at) VALUES('chat','ai',?,'[\"builtin-coding\"]',0)", BuiltinAgents.CODING);
            jdbc.update("INSERT INTO roundtable_todo(id,session_id,owner_peer,title,status,seq,created_at,updated_at) VALUES('todo','chat',?,'work','doing',0,0,0)", BuiltinAgents.CODING);
            jdbc.update("INSERT INTO collaboration_plan(session_id,coordinator_id,version,phase,todo_id,executor_id,reviewer_id) VALUES('chat','builtin',2,'executing','todo',?,'builtin')", BuiltinAgents.CODING);
            jdbc.update("INSERT INTO collaboration_assignment VALUES('chat','todo',2,?,'builtin','executing','old dispatch')", BuiltinAgents.CODING);
            var memories = new cc.ginpika.minis.ai.memory.graph.MemoryGraphStore(jdbc).claims();
            long memory = memories.insert(new cc.ginpika.minis.ai.memory.store.MemoryItemStore.Ownership(
                    null, "AGENT", "AGENT", "L2", null, null, null, BuiltinAgents.CODING),
                    new cc.ginpika.minis.ai.memory.store.MemoryItemStore.Governance(BuiltinAgents.CODING,
                            "inferred", "inferred", "long", cc.ginpika.minis.ai.core.MemoryIdentity.REAL),
                    "method", "coding note", "preserved coding memory", 0.9, "auto", null, null);
            String oldFingerprint = memories.findById(memory).orElseThrow().fingerprint();
            jdbc.update("UPDATE _schema SET version=5");
            var migration = new SchemaMigrations(jdbc, tx, "jdbc:sqlite::memory:");
            migration.apply(); migration.apply();

            assertEquals(6, jdbc.queryForObject("SELECT version FROM _schema", Integer.class));
            assertEquals(1, agents.peers().size());
            assertTrue(agents.peer(BuiltinAgents.CODING).isEmpty());
            assertEquals("old-model", jdbc.queryForObject("SELECT model_id FROM agent WHERE id=?", String.class, BuiltinAgents.CODING));
            assertEquals(BuiltinAgents.CORE, coding.profile(run.id()).agentId());
            assertEquals("frozen-instructions", coding.profile(run.id()).instructions());
            assertEquals("frozen-model", coding.run(run.id(), "alice").modelId());
            assertEquals(BuiltinAgents.CORE, jdbc.queryForObject("SELECT agent_id FROM task WHERE id=?", String.class, run.taskId()));
            assertEquals(BuiltinAgents.CORE, jdbc.queryForObject("SELECT agent_id FROM hub_session WHERE id='chat'", String.class));
            assertEquals("[\"builtin\"]", jdbc.queryForObject("SELECT member_ids FROM hub_session WHERE id='chat'", String.class));
            assertEquals(BuiltinAgents.CORE, jdbc.queryForObject("SELECT owner_peer FROM roundtable_todo WHERE id='todo'", String.class));
            assertEquals("planning", jdbc.queryForObject("SELECT phase FROM collaboration_plan WHERE session_id='chat'", String.class));
            assertEquals(3, jdbc.queryForObject("SELECT version FROM collaboration_plan WHERE session_id='chat'", Integer.class));
            assertEquals("old dispatch", jdbc.queryForObject("SELECT decision FROM collaboration_assignment WHERE todo_id='todo'", String.class));
            assertEquals(BuiltinAgents.CORE, memories.findById(memory).orElseThrow().agentId());
            assertNotEquals(oldFingerprint, memories.findById(memory).orElseThrow().fingerprint());
            assertEquals("preserved coding memory", memories.findById(memory).orElseThrow().body());
            var global = new PromptStore(jdbc);
            var binding = global.bindings().stream().filter(b -> CodingSkill.KEY.equals(b.promptKey())).findFirst().orElseThrow();
            assertTrue(global.version(binding.versionId()).orElseThrow().content().contains("preserved-user-coding-rules"));
            assertTrue(global.versions().stream().noneMatch(v -> "identity".equals(v.promptKey())),
                    "Merging must not replace the default agent's persona");
            assertEquals(1, legacy.versions(BuiltinAgents.CODING).size());
            global.saveBinding(CodingSkill.KEY, null);
            new CodingAgentMerge(jdbc).apply();
            assertNull(global.bindings().stream().filter(b -> CodingSkill.KEY.equals(b.promptKey())).findFirst().orElseThrow().versionId());
        } finally { source.destroy(); }
    }
}
