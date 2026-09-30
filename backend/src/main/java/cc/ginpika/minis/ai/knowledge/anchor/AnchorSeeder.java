package cc.ginpika.minis.ai.knowledge.anchor;

import cc.ginpika.minis.ai.knowledge.store.ChunkDocMigrator;
import cc.ginpika.minis.ai.knowledge.store.GraphStore;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/**
 * 锚点播种（幂等，启动时执行一次）：把 {@link GraphAnchors#ALL} 逐条落进 entities 表。
 *
 * <p>不存在的建（主 type 即锚点标记 {@link GraphAnchors#TYPE}）；已存在的（如文档导入先建过
 * 「用户」）只并入锚点标记，不动其主类型 / 别名 / 摘要。重复启动与锚点已就位时均无副作用。
 *
 * <p>必须晚于 {@link ChunkDocMigrator}：entities.types 列由它补出，先于它写 types 会因缺列失败
 * （同 {@link cc.ginpika.minis.agent.subconscious.SubconsciousSeeder} 之于 subconsciousMigrator 的时序约定）。
 */
@Component
@DependsOn("chunkDocMigrator")
public class AnchorSeeder {

    private static final Logger log = LoggerFactory.getLogger(AnchorSeeder.class);

    private final GraphStore graph;

    public AnchorSeeder(GraphStore graph) {
        this.graph = graph;
        seed();
    }

    private void seed() {
        int created = 0;
        int marked = 0;
        for (GraphAnchors.Anchor anchor : GraphAnchors.ALL) {
            GraphStore.Entity exist = graph.byName(anchor.name());
            if (exist == null) {
                graph.upsertEntity(GraphAnchors.TYPE, anchor.name(), anchor.aliases(), anchor.semantics());
                created++;
            } else if (!GraphAnchors.isAnchor(exist)) {
                graph.mergeEntityTypes(anchor.name(), List.of(GraphAnchors.TYPE));
                marked++;
            }
        }
        if (created + marked > 0) {
            log.info("图谱锚点：新建 {} 个、标记 {} 个（{}）", created, marked,
                    GraphAnchors.ALL.stream().map(GraphAnchors.Anchor::name).toList());
        }
    }
}
