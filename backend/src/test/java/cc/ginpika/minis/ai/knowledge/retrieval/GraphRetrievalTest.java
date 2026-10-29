package cc.ginpika.minis.ai.knowledge.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.ginpika.minis.ai.knowledge.ingest.ResultTexts;
import cc.ginpika.minis.ai.knowledge.retrieval.GraphExplorer;
import cc.ginpika.minis.ai.knowledge.store.GraphStore;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * CHAT_GRAPH_RAG.md 改进的回归冒烟（内存 SQLite，不起 Spring 上下文）：
 * P0-1 溯源渲染、P0-2 实体向量召回与 LIKE 相关度排序、P1-1 PPR 共同邻居上浮。
 */
class GraphRetrievalTest {

    /** 张伟/李娜 --任职于--> 星尘科技（无直接边）+ 各自一条噪声边；另备「星尘」前缀实体做排序验证 */
    private static GraphStore seed() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE entities(id TEXT PRIMARY KEY, type TEXT NOT NULL, types TEXT, "
                + "name TEXT NOT NULL UNIQUE, aliases TEXT, summary TEXT, meta TEXT, "
                + "created_at INTEGER NOT NULL, updated_at INTEGER)");
        jdbc.execute("CREATE TABLE relations(id TEXT PRIMARY KEY, subject_id TEXT NOT NULL, "
                + "predicate TEXT NOT NULL, object_id TEXT NOT NULL, props TEXT, source_chunk INTEGER)");
        jdbc.execute("CREATE TABLE chunk_entity(chunk_id INTEGER NOT NULL, entity_id TEXT NOT NULL, "
                + "PRIMARY KEY(chunk_id, entity_id))");
        jdbc.execute("CREATE TABLE entity_embedding(id TEXT PRIMARY KEY, model TEXT NOT NULL, "
                + "dim INTEGER NOT NULL, vec BLOB NOT NULL, updated_at INTEGER NOT NULL)");
        GraphStore store = new GraphStore(jdbc, new ObjectMapper());
        try { org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(ds.getConnection(),new org.springframework.core.io.ClassPathResource("schema.sql")); }
        catch(Exception e) { throw new IllegalStateException(e); }
        for(long id:List.of(7L,8L,9L)) jdbc.update("INSERT INTO chunks(id,uuid,source,content,created_at) VALUES(?,?,'test','文档来源',0)",id,"chunk-"+id);
        store.upsertEntity("PERSON", "张伟", List.of(), null);
        store.upsertEntity("PERSON", "李娜", List.of(), null);
        store.upsertEntity("ORG", "星尘科技", List.of(), null);
        store.upsertEntity("ORG", "星尘研究院", List.of(), null);
        store.upsertEntity("CONCEPT", "星尘计划", List.of(), null);
        store.upsertEntity("ORG", "银河网络", List.of(), null);
        store.upsertEntity("PLACE", "某大学", List.of(), null);
        String zhang = store.byName("张伟").id();
        String li = store.byName("李娜").id();
        String xing = store.byName("星尘科技").id();
        store.upsertRelation(zhang, "任职于", xing, 7L);
        store.upsertRelation(li, "任职于", xing, 8L);
        store.upsertRelation(zhang, "毕业于", store.byName("某大学").id(), null);
        store.upsertRelation(li, "前雇主", store.byName("银河网络").id(), 9L);
        return store;
    }

    @Test
    void pprSurfacesCommonNeighborOnTop() {
        GraphStore store = seed();
        GraphExplorer.Exploration ex = new GraphExplorer(store).explore(
                Set.of(store.byName("张伟").id(), store.byName("李娜").id()),
                240, 12, 30);
        /* 共同邻居「星尘科技」应浮到首位（两端各只有一条噪声边） */
        assertEquals("星尘科技", ex.top().get(0).entity().name());
        /* 两条「任职于」边都应带回 */
        long hired = ex.edges().stream().filter(e -> "任职于".equals(e.predicate())).count();
        assertEquals(2, hired);
    }

    @Test
    void lookupOrdersByRelevanceNotInsertion() {
        GraphStore store = seed();
        List<GraphStore.Entity> hits = store.lookup(List.of("星尘"), 10);
        /* 名字更短者优先：星尘科技/星尘计划(4字) 在 星尘研究院(5字) 前 */
        assertEquals("星尘科技", hits.get(0).name());
        assertTrue(hits.get(hits.size() - 1).name().equals("星尘研究院"));
    }

    @Test
    void entityVectorRecallByCosine() {
        GraphStore store = seed();
        store.saveEntityEmbedding(store.byName("星尘科技").id(), "m1", new float[]{1f, 0f});
        store.saveEntityEmbedding(store.byName("银河网络").id(), "m1", new float[]{0f, 1f});
        List<GraphStore.EntitySimilarity> hits =
                store.entitiesByCosine("m1", new float[]{0.9f, 0.1f}, 5);
        assertEquals(store.byName("星尘科技").id(), hits.get(0).entityId());
        /* model 不匹配（无该模型向量）→ 空 */
        assertTrue(store.entitiesByCosine("other", new float[]{1f, 0f}, 5).isEmpty());
    }

    @Test
    void relationsTextCarriesPropsAndSource() {
        GraphStore.Entity zhang = new GraphStore.Entity("z", "PERSON", List.of(), "张伟", null, null, List.of(), null, null);
        GraphStore.Entity xing = new GraphStore.Entity("x", "ORG", List.of(), "星尘科技", null, null, List.of(), null, null);
        GraphStore.Edge edge = new GraphStore.Edge("e", "z", "任职于", "x",
                List.of(new GraphStore.Prop("职位", "工程师", "text")), 7L);
        String text = ResultTexts.graphRelationsText(zhang, List.of(edge), List.of(xing), Map.of(7L, "《2024入职通知》"));
        assertTrue(text.contains("张伟 --任职于--> 星尘科技"));
        assertTrue(text.contains("属性: 职位=工程师"));
        assertTrue(text.contains("来源: 《2024入职通知》"));
        /* 无 props/来源的边不带空括号后缀 */
        GraphStore.Edge bare = new GraphStore.Edge("e2", "z", "毕业于", "x", List.of(), null);
        String bareText = ResultTexts.graphRelationsText(zhang, List.of(bare), List.of(xing), Map.of());
        assertTrue(bareText.contains("- 张伟 --毕业于--> 星尘科技"));
        assertTrue(bareText.lines().noneMatch(l -> l.endsWith("（）")));
    }

    @Test
    void triplesTextRendersStructureEvidence() {
        GraphStore.Entity zhang = new GraphStore.Entity("z", "PERSON", List.of(), "张伟", null, null, List.of(), null, null);
        GraphStore.Entity xing = new GraphStore.Entity("x", "ORG", List.of(), "星尘科技", null, null, List.of(), null, null);
        GraphStore.Edge edge = new GraphStore.Edge("e", "z", "任职于", "x", List.of(), 7L);
        String text = ResultTexts.graphTriplesText(new EntityEnricher.Result(
                List.of(zhang, xing), List.of(zhang, xing), List.of(edge), List.of()));
        assertTrue(text.contains("锚点实体: 张伟（PERSON）、星尘科技（ORG）"));
        assertTrue(text.contains("- 张伟 --任职于--> 星尘科技"));
        assertTrue(ResultTexts.graphTriplesText(null).isEmpty());
    }
}
