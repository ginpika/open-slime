package cc.ginpika.minis.stats;

import cc.ginpika.minis.stats.dto.StatsDtos;
import cc.ginpika.minis.stats.store.TokenUsageStore;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class TokenUsageServiceTest {
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private TokenUsageStore store;
    private TokenUsageService service;
    private final ZoneId zone=ZoneId.of("Asia/Shanghai");
    @BeforeEach void setup() throws Exception {
        dataSource=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);
        jdbc=new JdbcTemplate(dataSource);
        String schema=new ClassPathResource("schema.sql").getContentAsString(StandardCharsets.UTF_8);
        String tokenSchema=schema.substring(schema.indexOf("CREATE TABLE IF NOT EXISTS token_usage ("),schema.indexOf("-- ---------------------------------------------------------------------------",schema.indexOf("CREATE TABLE IF NOT EXISTS token_usage (")));
        new ResourceDatabasePopulator(new ByteArrayResource(tokenSchema.getBytes(StandardCharsets.UTF_8))).execute(dataSource);
        store=new TokenUsageStore(jdbc);
        service=new TokenUsageService(store,Clock.fixed(Instant.parse("2026-10-15T04:00:00Z"),zone));
    }
    @AfterEach void close(){dataSource.destroy();}
    private void row(String date,String source,String provider,String model,long input,long output,String quality,String status,long cached) {
        long time=LocalDate.parse(date).atTime(12,0).atZone(zone).toInstant().toEpochMilli();
        jdbc.update("INSERT INTO token_usage(source,provider,model,prompt_tokens,completion_tokens,total_tokens,estimated,quality,status,cached_tokens,reasoning_tokens,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                source,provider,model,input,output,input+output,"estimated".equals(quality)?1:0,quality,status,cached,0,time);
    }
    private void price(String provider,String model,String date,String currency,String input,String output,String cached) {
        service.savePrice(new StatsDtos.PriceRequest(provider,model,date,currency,new BigDecimal(input),new BigDecimal(output),cached==null?null:new BigDecimal(cached)));
    }
    @Test void allDimensionsUseTheSameWindowAndIncludeZeroDaysAndFilters() {
        row("2026-09-01","chat","https://a.test/v1","same",1000,0,"reported","success",0);
        row("2026-10-15","chat","https://a.test/v1","same",80,20,"reported","success",0);
        row("2026-10-14","coding","https://b.test/v1","same",50,50,"estimated","success",0);
        var view=service.view(7);
        assertEquals(200,view.total().totalTokens());
        assertEquals(7,view.daily().size());
        assertEquals("2026-10-09",view.from()); assertEquals("Asia/Shanghai",view.timeZone());
        assertEquals(200,view.daily().stream().mapToLong(StatsDtos.Bucket::totalTokens).sum());
        assertEquals(200,view.bySource().stream().mapToLong(StatsDtos.Bucket::totalTokens).sum());
        assertEquals(2,view.byModel().size()); // 同名不同端点不会合并
        var filtered=service.view(7,null,null,"coding","https://b.test/v1","same");
        assertEquals(100,filtered.total().totalTokens()); assertEquals(1,filtered.recentCalls().size());
        assertEquals(2,filtered.models().size()); assertEquals(2,filtered.sources().size());
        assertEquals("2026-10-01",service.view(-1).from());
        assertEquals(0,service.view(7,null,null,"missing",null,null).total().calls());
    }
    @Test void priceVersionsCurrenciesCachedInputsAndMissingCoverageAreHandledWithoutDoubleCounting() {
        price("https://a.test/v1","m","2026-10-01","CNY","1","2",null);
        price("https://a.test/v1","m","2026-10-14","CNY","3","4","0.5");
        price("https://a.test/v1","m","2026-10-16","CNY","999","999","999");
        price("https://b.test/v1","m","2026-10-01","USD","5","6",null);
        row("2026-10-13","chat","https://a.test/v1","m",1000000,1000000,"reported","success",0); // 3 CNY
        row("2026-10-15","chat","https://a.test/v1","m",1000000,1000000,"reported","success",200000); // 6.5 CNY
        row("2026-10-15","chat","https://b.test/v1","m",1000000,0,"estimated","success",0); // 5 USD
        row("2026-10-15","chat","https://b.test/v1","m",1000000,0,"reported","success",1000); // 缓存单价未知
        row("2026-10-15","chat","https://a.test/v1","m",10,10,"partial","interrupted",0);
        row("2026-10-15","chat","","m",10,0,"reported","success",0); // 历史端点未知
        var v=service.view(7);
        assertEquals(3,v.total().unpricedCalls());
        assertEquals(1,v.total().partialCalls()); assertEquals(1,v.total().estimatedCalls());
        assertEquals(1,v.total().unsuccessfulCalls());
        assertEquals(2,v.total().costs().size());
        assertEquals(0,new BigDecimal("9.5").compareTo(v.total().costs().get(0).amount()));
        assertEquals("CNY",v.total().costs().get(0).currency());
        assertEquals(0,new BigDecimal("5").compareTo(v.total().costs().get(1).amount()));
        var withUnknownCache=v.byModel().stream().filter(b->b.provider().equals("https://b.test/v1")).findFirst().orElseThrow();
        assertEquals(1,withUnknownCache.unpricedCalls()); // 不会连带把无缓存的调用也当成未计价
    }
    @Test void repricingAnEffectiveVersionIsExplicitAndDoesNotChangeOtherVersions() {
        price("https://a.test/v1","m","2026-10-01","CNY","0","0",null);
        row("2026-10-15","chat","https://a.test/v1","m",100,0,"reported","success",0);
        assertEquals(0,service.view(7).total().unpricedCalls());
        assertEquals(BigDecimal.ZERO,service.view(7).total().costs().getFirst().amount().stripTrailingZeros());
        price("https://a.test/v1","m","2026-10-01","CNY","2","3",null);
        assertEquals(1,store.prices().size());
        assertEquals(0,new BigDecimal("0.0002").compareTo(service.view(7).total().costs().getFirst().amount()));
        service.deletePrice(store.prices().getFirst().id());
        assertEquals(1,service.view(7).total().unpricedCalls());
        assertThrows(ResponseStatusException.class,()->service.deletePrice(999));
    }
    @Test void dateAndPriceValidationDoesNotTreatMissingPricesAsZero() {
        assertThrows(ResponseStatusException.class,()->service.view(30,LocalDate.parse("2026-10-16"),LocalDate.parse("2026-10-16"),null,null,null));
        assertThrows(ResponseStatusException.class,()->service.view(30,LocalDate.parse("2025-01-01"),LocalDate.parse("2026-10-15"),null,null,null));
        assertThrows(ResponseStatusException.class,()->service.view(30,LocalDate.parse("2026-10-01"),null,null,null,null));
        assertThrows(ResponseStatusException.class,()->service.savePrice(new StatsDtos.PriceRequest("https://a.test/v1","m","2026-10-01","CNY",null,BigDecimal.ZERO,null)));
        assertThrows(ResponseStatusException.class,()->price("not a url","m","2026-10-01","CNY","1","1",null));
        assertThrows(ResponseStatusException.class,()->price("","m","2026-10-01","bad","1","1",null));
        assertThrows(ResponseStatusException.class,()->price("","m","2026-10-01","CNY","-1","1",null));
        assertEquals("https://api.test/v1",TokenUsageService.providerOf("https://name:secret@API.test/v1/?api_key=secret#secret"));
    }
    @Test void dailyBoundariesWorkAcrossDstAndExcludeBothSidesOfTheRange() {
        var ny=ZoneId.of("America/New_York");
        service=new TokenUsageService(store,Clock.fixed(Instant.parse("2026-11-02T12:00:00Z"),ny));
        var start=LocalDate.parse("2026-11-01").atStartOfDay(ny).toInstant();
        var end=LocalDate.parse("2026-11-02").atStartOfDay(ny).toInstant();
        assertEquals(25,Duration.between(start,end).toHours());
        for (long time:new long[]{start.toEpochMilli()-1,start.toEpochMilli(),end.toEpochMilli()-1,end.toEpochMilli()})
            jdbc.update("INSERT INTO token_usage(source,model,prompt_tokens,total_tokens,created_at) VALUES('chat','m',1,1,?)",time);
        var view=service.view(1,LocalDate.parse("2026-11-01"),LocalDate.parse("2026-11-01"),null,null,null);
        assertEquals(2,view.total().calls()); assertEquals(2,view.daily().getFirst().totalTokens());
    }
    @Test void oldEstimatedAndReportedRowsStayDistinctAndIncompleteRowsAreNotBilled() {
        for(int estimated:new int[]{0,1})
            jdbc.update("INSERT INTO token_usage(source,model,prompt_tokens,total_tokens,estimated,created_at) VALUES('chat','m',10,10,?,?)",estimated,Instant.parse("2026-10-15T04:00:00Z").toEpochMilli());
        jdbc.update("INSERT INTO token_usage(source,model,prompt_tokens,total_tokens,created_at) VALUES('chat','m',0,10,?)",Instant.parse("2026-10-15T04:00:00Z").toEpochMilli());
        price("","m","2026-10-01","CNY","1","0",null);
        var view=service.view(1);
        assertEquals(1,view.total().reportedCalls()); assertEquals(1,view.total().estimatedCalls());
        assertEquals(1,view.total().partialCalls()); assertEquals(1,view.total().unpricedCalls());
    }
}

