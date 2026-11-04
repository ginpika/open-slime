package cc.ginpika.minis.coding;

import cc.ginpika.minis.coding.api.CodingDtos.*;
import cc.ginpika.minis.coding.store.CodingStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

class CodingStoreTest {
    private CodingStore store;private JdbcTemplate jdbc;private Run run;
    @BeforeEach void prepare(){
        var source=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE task(id INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT,status TEXT,title TEXT,agent_id TEXT,session_id TEXT,model_id TEXT,workspace_id TEXT,payload TEXT,origin TEXT,error TEXT,created_at INTEGER,updated_at INTEGER)");
        new ResourceDatabasePopulator(new ClassPathResource("coding/schema.sql")).execute(source);
        store=new CodingStore(jdbc,new TransactionTemplate(new DataSourceTransactionManager(source)),new ObjectMapper());
        var repo=store.register("alice","Repo","/fixture/repo",null,null);
        run=store.create("alice",new StartRun(repo.id(),"Fix a bug",null,Mode.EDIT,Runner.DOCKER,10),10);
    }
    @Test void repositoryAndRunOwnershipAreEnforcedAtQueryBoundary(){
        assertNull(store.run(run.id(),"bob"));assertTrue(store.runs("bob").isEmpty());assertTrue(store.repositories("bob").isEmpty());
        assertNull(store.repository(run.repositoryId(),"bob"));assertEquals(run.id(),store.run(run.id(),"alice").id());
    }
    @Test void codingActivityTracksLifecycleAndKeepsOwnersAndSessionsIsolated(){
        assertEquals(1,store.activities("alice",null).getFirst().pending());
        assertTrue(store.activities("alice",null).getFirst().working());
        assertTrue(store.activities("bob",null).isEmpty());assertTrue(store.activities(null,null).isEmpty());
        assertTrue(store.activities("alice","other-session").isEmpty());
        store.linkConversation("alice",run.id(),"session-1","planner");
        for(String phase:java.util.List.of("PREPARING","RUNNING","VERIFYING","CANCELLING","ACCEPTING")){
            store.phase(run.id(),phase,null);
            assertEquals(1,store.activities("alice","session-1").getFirst().doing());
            assertTrue(store.activities("alice",null).getFirst().working());
        }
        for(String phase:java.util.List.of("PAUSED","REVIEWABLE","CANCELLED","FAILED","INTERRUPTED")){
            store.phase(run.id(),phase,null);assertFalse(store.activities("alice",null).getFirst().working());
        }
        store.phase(run.id(),"COMPLETED",null);assertEquals(1,store.activities("alice",null).getFirst().done());
        store.nextAttempt(run.id());store.phase(run.id(),"QUEUED",null);
        assertTrue(store.activities("alice",null).getFirst().working());
    }
    @Test void taskAndCodingPhaseChangeTogether(){
        store.phase(run.id(),"REVIEWABLE",null);assertEquals("suspended",jdbc.queryForObject("SELECT status FROM task WHERE id=?",String.class,run.taskId()));
        assertEquals("REVIEWABLE",store.run(run.id(),"alice").status());
        store.phase(run.id(),"COMPLETED",null);assertEquals("done",jdbc.queryForObject("SELECT status FROM task WHERE id=?",String.class,run.taskId()));
    }
    @Test void recoveryMarksUnknownOperationsWithoutReplayingThem(){
        store.phase(run.id(),"RUNNING",null);store.beginOperation(run,"call-1","apply_patch","{}");store.recover();
        assertEquals("INTERRUPTED",store.run(run.id(),"alice").status());assertTrue(store.unknownOperations(run.id()));
        assertTrue(store.resultForCall(run.id(),"call-1").contains("UNKNOWN"));assertEquals(1,store.operations(run.id()).size());
    }
    @Test void inputAcknowledgementAndCheckpointArePersistedTogether(){
        store.input(run.id(),"Keep the public API unchanged");var pending=store.pendingInputs(run.id());
        var messages=new ObjectMapper().createArrayNode();messages.addObject().put("role","user").put("content",pending.getFirst().content());
        store.applyInputs(run.id(),messages,2,pending);
        assertTrue(store.pendingInputs(run.id()).isEmpty());assertEquals(messages.toString(),store.messages(run.id()));
        assertEquals(2,store.run(run.id(),"alice").round());
    }
    @Test void instructionReceiptsPersistAcceptedAppliedAndTrustedSource() {
        String text="用户新指令：修复文档";
        long input=store.input(run.id(),text,"COLLABORATION_AGENT","request-key",2);
        assertEquals(input,store.receipt(run.id(),"request-key").instructionId());
        assertEquals(text,store.receipt(run.id(),"request-key").acceptedInput());
        assertEquals("ACCEPTED",store.latestReceipt(run.id()).instructionState());
        var pending=store.pendingInputs(run.id());
        assertTrue(store.instructionText(run.id(),pending.getFirst()).contains("来源=COLLABORATION_AGENT"));
        store.applyInputs(run.id(),new ObjectMapper().createArrayNode(),1,pending);
        assertEquals("APPLIED",store.receipt(run.id(),"request-key").instructionState());
        assertThrows(org.springframework.dao.DataAccessException.class,()->store.input(run.id(),"duplicate","AGENT","request-key",2));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM coding_input WHERE run_id=?",Integer.class,run.id()));
    }
    @Test void eventReplayUsesStableCursorAndRunFilter(){
        long first=store.event(run.id(),"first",java.util.Map.of("value",1));store.event(run.id(),"second",java.util.Map.of("value",2));
        var rows=store.events(run.id(),first,10);assertEquals(1,rows.size());assertEquals("second",rows.getFirst().type());
        assertTrue(store.events("other-run",0,10).isEmpty());
    }
    @Test void reviewPermissionsAreDurableAndInitialPolicySurvivesContinuation(){
        assertFalse(store.autoReview(run.id()));assertFalse(store.requestedAutoReview(run.id()));
        store.reviewPolicy(run.id(),true);assertTrue(store.autoReview(run.id()));assertFalse(store.requestedAutoReview(run.id()));
        var created=store.create("alice",new StartRun(run.repositoryId(),"Review this",null,Mode.EDIT,Runner.DOCKER,10,null,true),10);
        assertTrue(store.autoReview(created.id()));assertTrue(store.requestedAutoReview(created.id()));
        store.reviewPolicy(created.id(),false);assertFalse(store.autoReview(created.id()));assertTrue(store.requestedAutoReview(created.id()));
        assertFalse(new CodingStore(jdbc,new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),new ObjectMapper()).autoReview(created.id()));
    }
    @Test void repeatedProviderCallIdsAreResolvedAgainstCheckpointAttemptAndRound(){
        var messages=new ObjectMapper().createArrayNode();
        store.checkpoint(run.id(),messages,0);
        long old=store.beginOperation(run,"reused-id","read_file","{}");store.endOperation(old,"DONE","old-round-result");
        store.checkpoint(run.id(),messages,1);
        store.beginOperation(run,"reused-id","apply_patch","{}");store.phase(run.id(),"RUNNING",null);store.recover();
        store.nextAttempt(run.id());
        assertTrue(store.resultForCall(run.id(),"reused-id").contains("UNKNOWN"));
        assertFalse(store.resultForCall(run.id(),"reused-id").contains("old-round-result"));
    }
    @Test void interruptedReadOnlyResultsDoNotRequireSideEffectApproval(){
        store.phase(run.id(),"RUNNING",null);store.beginOperation(run,"read-1","read_file","{}");store.recover();
        assertFalse(store.unknownOperations(run.id()));assertTrue(store.resultForCall(run.id(),"read-1").contains("NOT_RETURNED"));
    }
    @Test void codingTasksHaveTheSameDirectoryIdentityAndOwnerScopedVisibility(){
        assertEquals(cc.ginpika.minis.ai.core.BuiltinAgents.CORE,jdbc.queryForObject("SELECT agent_id FROM task WHERE id=?",String.class,run.taskId()));
        assertTrue(store.ownsTask("alice",run.taskId()));assertFalse(store.ownsTask("bob",run.taskId()));assertFalse(store.ownsTask(null,run.taskId()));
        jdbc.update("UPDATE task SET agent_id=NULL WHERE id=?",run.taskId());store.recover();
        assertEquals(cc.ginpika.minis.ai.core.BuiltinAgents.CORE,jdbc.queryForObject("SELECT agent_id FROM task WHERE id=?",String.class,run.taskId()));
    }
    @Test void effectiveAgentModelAndOriginalRequestRemainDistinctForIdempotency(){
        Run selected=store.create("alice",new StartRun(run.repositoryId(),"Task", "agent-model",Mode.PLAN,Runner.DOCKER,10),10,"request-1",null,"shared agent instructions");
        assertEquals("agent-model",selected.modelId());assertNull(store.requestedModel(selected.id()));
        assertEquals("shared agent instructions",store.profile(selected.id()).instructions());assertEquals(selected.id(),store.request("alice","request-1").id());
    }
    @Test void conversationLinkingIsOwnerScopedAndDoesNotDuplicateDelegationEvents(){
        store.linkConversation("bob",run.id(),"other-session","other-agent");
        assertNull(jdbc.queryForObject("SELECT session_id FROM task WHERE id=?",String.class,run.taskId()));
        store.linkConversation("alice",run.id(),"chat-session","planner");store.linkConversation("alice",run.id(),"chat-session","planner");
        assertEquals("chat-session",jdbc.queryForObject("SELECT session_id FROM task WHERE id=?",String.class,run.taskId()));
        assertEquals(1,store.events(run.id(),0,100).stream().filter(event->event.type().equals("agent.delegated")).count());
    }
}
