package cc.ginpika.minis.agent.roundtable;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

class CollaborationQualityTest {
    final ObjectMapper json=new ObjectMapper();
    SingleConnectionDataSource source;CollaborationQuality quality;String session,todo;
    @BeforeEach void setup(){
        source=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        var jdbc=new JdbcTemplate(source);session=new RoundtableSessionStore(jdbc).insert("[\"a\",\"b\"]");
        todo=new RoundtableTodoStore(jdbc).add(session,null,"a","决策所需实验","边界","b",null);
        quality=new CollaborationQuality(jdbc,json);
    }
    @AfterEach void close(){source.destroy();}
    JsonNode findings(String id,String severity) {var values=json.createArrayNode();values.addObject().put("id",id).put("severity",severity).put("location","docs/baseline.md:12").put("expected","示例使用实际 Hz").put("basis","验收标准 C1");return values;}
    @Test void oldTasksRemainReadableAndRecordsCannotCrossSessionBoundaries(){
        assertTrue(quality.find(session,todo).contract().isNull());
        assertTrue(quality.find(session,todo).findings().isArray());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->quality.find("other",todo));
    }
    @Test void contractsAndReviewSeverityRejectAmbiguousWorkBeforePersistence() throws Exception {
        assertThrows(IllegalArgumentException.class,()->CollaborationQuality.validateContract(json.readTree("{\"outcome\":\"产出\"}")));
        var contract=json.readTree("{\"outcome\":\"一份技术决策\",\"acceptanceCriteria\":[{\"id\":\"C1\",\"text\":\"列出实测证据和限制\"}],\"stopCondition\":\"足以支持选型后收工\"}");
        quality.contract(todo,contract);assertEquals(contract,quality.find(todo).contract());
        var minor=findings("F1","MINOR");var blocker=findings("F1","BLOCKER");
        assertThrows(IllegalArgumentException.class,()->CollaborationQuality.validateFindings(minor,false));
        assertThrows(IllegalArgumentException.class,()->CollaborationQuality.validateFindings(blocker,true));
        assertDoesNotThrow(()->CollaborationQuality.validateFindings(minor,true));
    }
    @Test void issuesSurviveLaterReviewsAndResolutionKeepsEvidence(){
        var first=findings("F1","BLOCKER");quality.review(todo,first,false,1,"发现 Hz 硬编码","r=h1");
        assertTrue(quality.repeatsBlockers(todo,first,"r=h1"));assertFalse(quality.repeatsBlockers(todo,first,"r=h2"));
        quality.review(todo,findings("F2","BLOCKER"),false,2,"新增缺陷","r=h2");
        assertEquals(2,quality.find(todo).findings().size(),"不能因新一轮未列出旧问题就丢弃旧问题");
        quality.review(todo,findings("N1","MINOR"),true,3,"读取实际文件，所有阻塞问题已修复","r=h3");
        var saved=quality.find(todo).findings();assertEquals(3,saved.size());
        assertEquals("RESOLVED",saved.get(0).path("status").asText());assertTrue(saved.get(0).path("resolutionEvidence").asText().contains("实际文件"));
        assertEquals("ADVISORY",saved.get(2).path("status").asText());
    }
}
