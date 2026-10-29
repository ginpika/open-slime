package cc.ginpika.minis.coding;

import cc.ginpika.minis.agent.roundtable.*;
import cc.ginpika.minis.ai.core.AgentScope;
import cc.ginpika.minis.coding.api.CodingDtos.*;
import cc.ginpika.minis.coding.store.CodingStore;
import cc.ginpika.minis.project.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Same persona and project do not grant access to a sibling TODO's writable copy. */
class ParallelCodingAuthorityTest {
    @TempDir Path temp;
    @Test void runtimeTodoScopeSeparatesSameAgentAssignmentsAndReopenRevokesTheWholeWave() {
        var source=new DriverManagerDataSource("jdbc:sqlite:"+temp.resolve("authority.db"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql"),new ClassPathResource("coding/schema.sql")).execute(source);
        var jdbc=new JdbcTemplate(source);var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        var store=new CodingStore(jdbc,tx,new ObjectMapper());var repo=store.register("alice","fixture","workspace://fixture",null,null);
        new ProjectStore(jdbc).insert("project",null,"Project","software",false,"active","");
        String session=new RoundtableSessionStore(jdbc).insert("[\"worker\",\"reviewer\"]");
        jdbc.update("UPDATE hub_session SET project_id='project' WHERE id=?",session);
        new ProjectWorkspaceStore(jdbc).bind("project",repo.id());
        var board=new RoundtableTodoStore(jdbc);String first=board.add(session,null,"worker","A","A","reviewer",null),second=board.add(session,null,"worker","B","B","reviewer",null);
        var plans=new CollaborationPlan(jdbc,new ProjectKnowledgeStore(jdbc));plans.create(session,"reviewer");plans.revise(session,"tests","independent work");
        assertTrue(Boolean.TRUE.equals(tx.execute(s->plans.dispatchBatch(session,List.of(new CollaborationPlan.Dispatch(first,"worker","reviewer","executing","A"),new CollaborationPlan.Dispatch(second,"worker","reviewer","executing","B")),"parallel"))));
        board.claimAssigned(session,"worker",first,false).orElseThrow();board.claimAssigned(session,"worker",second,false).orElseThrow();
        var authority=new ProjectWorkExecution(jdbc);
        assertTrue(AgentScope.run("worker",()->authority.assignment("alice",session)).isEmpty(),"无 TODO 作用域时不能随机选一个写者身份");
        String runA,runB;
        try(var scope=ProjectWorkScope.bind(first)) {
            var assignment=authority.executor("alice",session,"worker");assertEquals(first,assignment.todo());
            Run run=store.create("alice",new StartRun(repo.id(),"A",null,Mode.EXECUTE,Runner.WORKSPACE,0),0);runA=run.id();authority.attach(assignment,runA);
        }
        try(var scope=ProjectWorkScope.bind(second)) {
            var assignment=authority.executor("alice",session,"worker");assertEquals(second,assignment.todo());
            Run run=store.create("alice",new StartRun(repo.id(),"B",null,Mode.EXECUTE,Runner.WORKSPACE,0),0);runB=run.id();authority.attach(assignment,runB);
            assertThrows(IllegalArgumentException.class,()->authority.continueRun("alice",session,"worker",runA));
        }
        authority.assertLive(runA);authority.assertLive(runB);
        int version=plans.find(session).orElseThrow().version();
        board.transition(first,session,"doing","reviewing","A result",null);plans.checking(session,first,version);board.claimAssigned(session,"reviewer",first,true).orElseThrow();
        try(var scope=ProjectWorkScope.bind(first)) {
            assertEquals(first,authority.reviewer("alice",session,"reviewer",runA).todo());
            assertThrows(IllegalArgumentException.class,()->authority.reviewer("alice",session,"reviewer",runB));
        }
        authority.assertLive(runB);assertThrows(CancellationException.class,()->authority.assertLive(runA));
        plans.reopen(session);
        assertThrows(CancellationException.class,()->authority.assertLive(runB));assertTrue(plans.assignments(session).isEmpty());
        board.transition(first,session,"checking","reviewing",null,null);
        assertTrue(plans.dispatch(session,first,"worker","reviewer","checking","仅重新派发保留成果的独立检查"));
        board.claimAssigned(session,"reviewer",first,true).orElseThrow();
        assertEquals(first,authority.reviewer("alice",session,"reviewer",runA).todo(),"新检查派工可以验证旧副本，无需重做实现");
        assertThrows(CancellationException.class,()->authority.assertLive(runA),"不能把新检查授权转换为旧执行授权");
        assertNull(ProjectWorkScope.get());
    }
}
