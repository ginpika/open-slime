package cc.ginpika.minis.ai.knowledge.dto;

import cc.ginpika.minis.ai.knowledge.store.GraphStore;
import java.util.List;

/** /api/search 与图预览 DTO */
public final class SearchDtos {

    private SearchDtos() {
    }

    /** rag=true：RAG 检索模式（测试暗门）——全量召回且只返回 chunk 命中 */
    public record SearchRequest(String query, Boolean rag) {
    }

    public record SearchResponse(String query, List<Hit> hits, Graph graph, Web web) {
    }

    /** kind: entity=图谱锚点实体；doc=文档；snapshot=网页快照（sourceRef 是 snapshot:UUID，跳本地详情）；url 为文章 slug；
     *  status: kind=chunk（RAG 模式）时为该块状态 unanalyzed|enabled|disabled，其余为 null */
    public record Hit(String kind, Long chunkId, String entityId, String title,
                      String snippet, String source, String sourceRef, String url,
                      double score, List<String> matched, String status) {
    }

    public record Graph(List<Node> nodes, List<Edge> edges) {
        public static final Graph EMPTY = new Graph(List.of(), List.of());
    }

    /** createdAt/updatedAt：实体行级时间戳（毫秒 epoch，可空），前端在 Props 区只读展示 */
    public record Node(String id, String name, String type, List<GraphStore.Prop> props,
                       Long createdAt, Long updatedAt) {
    }

    public record Edge(String id, String from, String to, String predicate,
                       List<GraphStore.Prop> props) {
    }

    /** Local and remote scores have different scales and are intentionally not merged. */
    public record WebResult(String title, String url, String snippet, String summary, String content,
                            String siteName, String date, double score, int authority) {}

    public record Web(boolean enabled, List<WebResult> results, String error, List<String> warnings) {
        public static final Web DISABLED = new Web(false, List.of(), null, List.of());
        public Web(boolean enabled, List<WebResult> results) { this(enabled, results, null, List.of()); }
    }
}
