package cc.ginpika.minis.ai.knowledge.anchor;

import cc.ginpika.minis.ai.knowledge.store.GraphStore;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 锚点播种（幂等，启动时执行一次）：把 {@link GraphAnchors#ALL} 逐条落进 entities 表。
 *
 * <p>不存在的建（主 type 即锚点标记 {@link GraphAnchors#TYPE}）；已存在的（如文档导入先建过
 * 「用户」）只并入锚点标记，不动其主类型 / 别名 / 摘要。重复启动与锚点已就位时均无副作用。
 */
@Component
public class AnchorSeeder {

    private static final Logger log = LoggerFactory.getLogger(AnchorSeeder.class);

    private final GraphStore graph;

    public AnchorSeeder(GraphStore graph) {
        this.graph = graph;
        seed();
    }

    /**
     * 幂等播种全部锚点。除启动时构造调用外，设置页「重置知识库」清空 entities 后也会再调一次
     * ——锚点会被 clearAll 一并删掉，须即时补种，否则用户画像等依赖锚点的功能要到重启才恢复。
     */
    public void seed() {
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
