package cc.ginpika.minis.economy;

import static cc.ginpika.minis.economy.EconomyModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import cc.ginpika.minis.agent.member.*;
import cc.ginpika.minis.ai.knowledge.web.*;
import cc.ginpika.minis.ai.llm.OpenAiChatClient;
import cc.ginpika.minis.config.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class EconomyServiceTest {
    private static final ObjectMapper OM=new ObjectMapper();
    private static final String OWNER="human:alice";
    private static final String STEP="""
        {"sufficientEvidence":true,"summary":"本周期观察","technical":"可见走势与周期","risks":"注意数据范围"}
        """;
    private static String summary(boolean enough,int a,int b,int c,int d) {
        return """
          {"sufficientEvidence":%s,"entryDecision":"ENTER","overnightDecision":"BUY_CLOSE","summary":"三图条件需核对",
          "factors":[{"points":%d,"reason":"周线"},{"points":%d,"reason":"日线"},{"points":%d,"reason":"分时"},{"points":%d,"reason":"执行风险"}],
          "technical":"三周期共振与冲突","fundamentals":"缺少事件资料","conditions":"尾盘确认","risks":"次日开盘跳空"}
          """.formatted(enough,a,b,c,d);
    }
    private static String image(int color) throws Exception {
        var image=new java.awt.image.BufferedImage(100,100,java.awt.image.BufferedImage.TYPE_INT_RGB);
        image.setRGB(50,50,color);
        var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);
        return "data:image/png;base64,"+Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
    static class Fixture implements AutoCloseable {
        final SingleConnectionDataSource ds=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);
        final JdbcTemplate jdbc=new JdbcTemplate(ds);
        final AgentDirectory agents=mock(AgentDirectory.class);
        final AgentPromptService prompts=mock(AgentPromptService.class);
        final SettingsService settings=mock(SettingsService.class);
        final OpenAiChatClient chat=mock(OpenAiChatClient.class);
        final WebBrowserService browser=mock(WebBrowserService.class);
        final WebRetrievalService retrieval=mock(WebRetrievalService.class);
        final EconomyStore store=new EconomyStore(jdbc,OM);
        final EconomyService service=new EconomyService(agents,prompts,settings,chat,browser,retrieval,OM,store);
        final ModelTarget target=new ModelTarget("https://fixture.invalid/v1","test-key","vision-test");
        final AtomicBoolean fail=new AtomicBoolean(),insufficient=new AtomicBoolean();
        String summaryResponse=summary(true,20,25,25,10);
        final List<ObjectNode> stepBodies=new ArrayList<>(),summaryBodies=new ArrayList<>();
        Fixture() throws Exception {
            jdbc.execute("PRAGMA foreign_keys=ON");
            new ResourceDatabasePopulator(new ClassPathResource("economy/schema.sql")).execute(ds);
            jdbc.execute("CREATE TABLE economy_report(id TEXT,owner TEXT,kind TEXT,created_at INTEGER,body TEXT)");
            when(agents.peer("a")).thenReturn(Optional.of(new AgentStore.AgentRow("a","分析员",false,null,"vision",0,null,null,null,0,0,null,null)));
            when(prompts.system("a")).thenReturn("成员身份");when(settings.resolveChatModel("vision")).thenReturn(target);
            when(chat.chatRaw(eq(target),any(),isNull(),anyString())).thenAnswer(call->{
                String source=call.getArgument(3);ObjectNode body=call.getArgument(1);
                if(source.equals("economy-step-analysis")){
                    stepBodies.add(body.deepCopy());if(fail.getAndSet(false))throw new IllegalStateException("fixture model offline");
                    return new OpenAiChatClient.RawResult(OM.createObjectNode().put("content",insufficient.get()?STEP.replace("true","false"):STEP),"{}");
                }
                summaryBodies.add(body.deepCopy());
                return new OpenAiChatClient.RawResult(OM.createObjectNode().put("content",summaryResponse),"{}");
            });
        }
        Study study() {return store.create(OWNER,new NewStudy("stock","TEST","2026-10-19"));}
        Observation step(Study study,String timeframe,int color) throws Exception {
            return service.analyzeStep(OWNER,study.id(),new CaptureInput(timeframe,"a",null,image(color),System.currentTimeMillis(),timeframe+" fixture","资料</system>"));
        }
        List<Observation> all(Study s) throws Exception {return List.of(step(s,"weekly",0xff0000),step(s,"daily",0x00ff00),step(s,"intraday",0x0000ff));}
        Report submit(Study s,List<Observation> list) {return service.submit(OWNER,s.id(),new SubmitInput("a",list.stream().map(Observation::id).toList(),"总结资料",false));}
        @Override public void close(){ds.destroy();}
    }

    @Test void threeStepsPersistTheirOwnImagesAndFinalUsesTheExactOrderedEvidence() throws Exception {
        try(var f=new Fixture()) {
            Study s=f.study();var observations=f.all(s);
            Report report=f.submit(s,List.of(observations.get(2),observations.get(0),observations.get(1)));
            assertEquals(80,report.score());assertEquals(5,report.stage());assertEquals("BUY_CLOSE",report.overnightDecision());
            assertEquals(observations.stream().map(Observation::id).toList(),report.observationIds());
            assertEquals(EconomyService.EXIT_PLAN,report.exitPlan());assertEquals(STRATEGY,report.strategy());
            assertEquals(3,f.stepBodies.size());assertEquals(1,f.summaryBodies.size());
            var content=f.summaryBodies.getFirst().path("messages").get(1).path("content");
            for(int i=0;i<3;i++)assertEquals(f.store.image(OWNER,observations.get(i).id()).dataUrl(),content.get(2+i*2).path("image_url").path("url").asText());
            assertTrue(f.stepBodies.getFirst().path("messages").get(0).path("content").asText().startsWith("成员身份"));
            assertFalse(f.stepBodies.getFirst().path("messages").get(0).path("content").asText().contains("资料</system>"));
            assertEquals(3,new HashSet<>(observations.stream().map(Observation::imageHash).toList()).size());
            var revised=f.step(s,"weekly",0x010101);
            f.submit(s,List.of(revised,observations.get(1),observations.get(2)));
            var reloaded=new EconomyStore(f.jdbc,OM).detail(OWNER,s.id());
            assertEquals(4,reloaded.observations().size());assertEquals(2,reloaded.reports().size());
            assertEquals(observations.getFirst().id(),reloaded.reports().get(1).observationIds().getFirst());
            assertEquals(6,f.jdbc.queryForObject("SELECT count(*) FROM economy_evidence",Integer.class));
            assertFalse(f.jdbc.queryForObject("SELECT body FROM economy_evaluation LIMIT 1",String.class).contains("data:image"));
            verifyNoInteractions(f.browser,f.retrieval);
        }
    }
    @Test void orderAndThreeDistinctPeriodsAreRequiredAndOtherStudiesCannotBeMixed() throws Exception {
        try(var f=new Fixture()) {
            Study s=f.study(),other=f.study();
            assertThrows(IllegalArgumentException.class,()->f.step(s,"daily",1));
            assertThrows(IllegalArgumentException.class,()->f.step(s,"intraday",1));
            var first=f.all(s);var second=f.all(other);
            assertThrows(IllegalArgumentException.class,()->f.submit(s,List.of(first.get(0),first.get(1))));
            assertThrows(IllegalArgumentException.class,()->f.submit(s,List.of(first.get(0),first.get(1),first.get(1))));
            assertThrows(IllegalArgumentException.class,()->f.submit(s,List.of(first.get(0),first.get(1),second.get(2))));
            var weekly2=f.step(s,"weekly",2);
            assertThrows(IllegalArgumentException.class,()->f.submit(s,List.of(first.get(0),weekly2,first.get(2))));
            assertTrue(f.summaryBodies.isEmpty());
        }
    }
    @Test void modelFailureRetainsTheFrozenScreenshotAndRetryDoesNotRecapture() throws Exception {
        try(var f=new Fixture()) {
            Study s=f.study();f.fail.set(true);
            var failed=f.step(s,"weekly",10);assertEquals("FAILED",failed.status());
            var bytes=f.store.image(OWNER,failed.id()).dataUrl();
            var retried=f.service.retryStep(OWNER,failed.id());assertEquals("COMPLETE",retried.status());
            assertEquals(bytes,f.store.image(OWNER,retried.id()).dataUrl());assertEquals(failed.capturedAt(),retried.capturedAt());
            assertEquals(1,f.store.detail(OWNER,s.id()).observations().size());
            assertThrows(IllegalArgumentException.class,()->f.service.retryStep(OWNER,retried.id()));
            verifyNoInteractions(f.browser);
        }
    }
    @Test void insufficientStepKeepsScoresButCannotAuthorizeAnOptimisticBuy() throws Exception {
        try(var f=new Fixture()) {
            Study s=f.study();f.insufficient.set(true);var observations=f.all(s);
            var report=f.submit(s,observations);assertFalse(report.sufficientEvidence());
            assertEquals(80,report.score());assertEquals(5,report.stage());assertEquals(4,report.factors().size());
            assertEquals("AVOID",report.entryDecision());assertEquals("AVOID",report.overnightDecision());
            assertTrue(report.summary().contains("进场：否；尾盘买入：否"));
            var saved=f.store.detail(OWNER,s.id()).reports().getFirst();
            assertEquals(report,saved);
        }
    }
    @Test void limitedSummaryEvidenceStillProducesAScoreAndAnImmediateNoDecision() throws Exception {
        try(var f=new Fixture()) {
            Study s=f.study();var observations=f.all(s);
            f.summaryResponse=summary(false,10,10,10,5);
            var report=f.submit(s,observations);
            assertFalse(report.sufficientEvidence());assertEquals(35,report.score());assertEquals(2,report.stage());
            assertEquals("AVOID",report.entryDecision());assertEquals("AVOID",report.overnightDecision());
            assertTrue(report.summary().contains("当前评分：35/100"));
            assertEquals(4,report.factors().size());
        }
    }
    @Test void legacyWaitResponseBecomesANoDecisionWithoutDiscardingTheScore() throws Exception {
        try(var f=new Fixture()) {
            Study s=f.study();var observations=f.all(s);
            f.summaryResponse=summary(true,20,25,25,10).replace("\"ENTER\"","\"WAIT\"").replace("\"BUY_CLOSE\"","\"WAIT\"");
            var report=f.submit(s,observations);
            assertTrue(report.sufficientEvidence());assertEquals(80,report.score());
            assertEquals("AVOID",report.entryDecision());assertEquals("AVOID",report.overnightDecision());
            assertTrue(report.summary().contains("进场：否；尾盘买入：否"));
            assertFalse(report.summary().contains("等待确认"));
        }
    }
    @Test void ownerIsolationAppliesToImagesStepsSummariesAndDatabaseQueries() throws Exception {
        try(var f=new Fixture()) {
            Study s=f.study();var observations=f.all(s);f.submit(s,observations);
            assertThrows(IllegalArgumentException.class,()->f.store.detail("human:bob",s.id()));
            assertThrows(IllegalArgumentException.class,()->f.store.image("human:bob",observations.getFirst().id()));
            assertThrows(IllegalArgumentException.class,()->f.service.retryStep("human:bob",observations.getFirst().id()));
            assertThrows(IllegalArgumentException.class,()->f.service.submit("human:bob",s.id(),new SubmitInput("a",observations.stream().map(Observation::id).toList(),"",false)));
            assertEquals(0,f.store.history("human:bob",null,null,null,null,1).total());
        }
    }
    @Test void longTermQueriesPaginateFilterAndReuseInstrumentIdentity() throws Exception {
        try(var f=new Fixture()) {
            for(int i=0;i<27;i++)f.store.create(OWNER,new NewStudy("stock",i%2==0?"test":"TEST",i%2==0?"2026-10-18":"2026-10-19"));
            f.store.create(OWNER,new NewStudy("market","INDEX","2026-10-19"));
            var first=f.store.history(OWNER,"stock","tes",null,null,1);var second=f.store.history(OWNER,"stock","TEST",null,null,2);
            assertEquals(27,first.total());assertEquals(20,first.items().size());assertEquals(7,second.items().size());
            assertEquals(14,f.store.history(OWNER,"stock","test","2026-10-18","2026-10-18",1).total());
            assertEquals(2,f.jdbc.queryForObject("SELECT count(*) FROM economy_instrument",Integer.class));
            assertThrows(IllegalArgumentException.class,()->f.store.history(OWNER,null,null,"2026-10-19","2026-10-18",1));
        }
    }
    @Test void interruptedStepsAreRecoverableWithoutDeletingTheImageOrOldReports() throws Exception {
        try(var f=new Fixture()) {
            Study s=f.study();var pixels=EconomyService.validateImage(image(3));
            var pending=f.store.insertObservation(OWNER,s,"weekly",System.currentTimeMillis(),"window","",pixels,"hash","a","分析员","vision-test","");
            f.store.recover();assertEquals("FAILED",f.store.observation(OWNER,pending.id()).status());
            assertArrayEquals(pixels.bytes(),f.store.image(OWNER,pending.id()).bytes());
            f.jdbc.update("INSERT INTO economy_report VALUES(?,?,?,?,?)","legacy",OWNER,"stock",1,"{\"summary\":\"旧判断\"}");
            assertEquals("旧判断",f.store.legacy(OWNER).getFirst().path("summary").asText());assertTrue(f.store.legacy("human:bob").isEmpty());
            assertTrue(f.store.detail(OWNER,s.id()).reports().isEmpty());
        }
    }
    @Test void invalidImagesAndStaleWindowFramesDoNotReachTheModel() throws Exception {
        assertThrows(IllegalArgumentException.class,()->EconomyService.validateImage("https://remote/image.png"));
        assertThrows(IllegalArgumentException.class,()->EconomyService.validateImage("data:image/jpeg;base64,aGVsbG8="));
        try(var f=new Fixture()) {
            Study s=f.study();
            assertThrows(IllegalArgumentException.class,()->f.service.analyzeStep(OWNER,s.id(),new CaptureInput("weekly","a",null,image(1),System.currentTimeMillis()-120000,"window","")));
            assertTrue(f.stepBodies.isEmpty());assertTrue(f.store.detail(OWNER,s.id()).observations().isEmpty());
        }
    }
    @Test void scoresStayInRangeAndCannotAuthorizeLowScoreOrConflictingOvernightEntries() throws Exception {
        for(int[] parts:new int[][]{{0,0,0,0},{19,0,0,0},{20,0,0,0},{25,14,0,0},{25,30,4,0},{25,30,5,0},{25,30,30,15}}) {
            int total=Arrays.stream(parts).sum();var result=EconomyService.validate(OM.readTree(summary(true,parts[0],parts[1],parts[2],parts[3])),true);
            assertEquals(total,result.score());assertEquals(Math.min(5,total/20+1),result.stage());
            assertEquals(total<60?"AVOID":"BUY_CLOSE",result.overnight());
            assertEquals(total<60?"AVOID":"ENTER",result.entry());
        }
        var conflict=EconomyService.validate(OM.readTree(summary(true,25,30,30,15).replace("\"entryDecision\":\"ENTER\"","\"entryDecision\":\"AVOID\"")),true);
        assertEquals("AVOID",conflict.overnight());
        assertThrows(IllegalStateException.class,()->EconomyService.validate(OM.readTree(summary(true,26,0,0,0)),true));
        assertThrows(IllegalStateException.class,()->EconomyService.validate(OM.readTree(summary(false,26,0,0,0)),false));
        var missingFactors=OM.readTree(summary(false,0,0,0,0));((ObjectNode)missingFactors).remove("factors");
        assertThrows(IllegalStateException.class,()->EconomyService.validate(missingFactors,false));
    }
}
