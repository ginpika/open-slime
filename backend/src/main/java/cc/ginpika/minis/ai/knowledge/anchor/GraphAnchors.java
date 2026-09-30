package cc.ginpika.minis.ai.knowledge.anchor;

import cc.ginpika.minis.ai.knowledge.store.GraphStore;
import java.util.List;

/**
 * 知识图谱记忆维度的<b>锚点名册</b>（代码即声明）：系统预置的特殊实体——大模型认知世界的固定坐标。
 *
 * <p>锚点与普通实体同表同构（entities 表一行 + relations 关系），检索 / 探索工具对它一视同仁；
 * 唯一区别是 types 里带 {@link #TYPE} 标记，并由此获得两处特殊对待：
 * ① 由 {@link AnchorSeeder} 出厂播种（幂等，主 type 首写定不覆盖保证标记不被导入冲掉）；
 * ② 不可删除（KnowledgeToolbox 的 delete_graph_item 按本类拦截）。锚点上的<b>关系</b>照常可增可改。
 *
 * <p>锚点的意义：给大模型一个稳定的「第一人称坐标」。如 {@link #USER} 是「用户」概念的抽象——
 * 模型通过维护用户锚点与其它实体的关系（用户 —[喜欢]→ X、用户 —[从事]→ Y），在图谱上构建
 * 基于图的用户画像，实现对用户这一概念的认知与学习。
 *
 * <p>归属说明：锚点是图谱实体语义，故定义在知识域（ai.knowledge）而非编排层（agent）——
 * 图谱工具与检索侧都要识别它，见 arch.LayeringTest 钉死的单向依赖 agent → ai.knowledge。
 */
public final class GraphAnchors {

    /** 锚点标记：写入 entities.types（锚点 = types 含此值的实体；与 PERSON/ORG 等并存为多类型） */
    public static final String TYPE = "ANCHOR";

    /** 一条锚点声明：实体名 / 别名 / 语义说明（播种落库为 summary，也是提示词引用的唯一事实源） */
    public record Anchor(String name, List<String> aliases, String semantics) {
    }

    /** 用户锚点：「用户」概念的抽象，模型构建与维护用户画像的圆心 */
    public static final Anchor USER = new Anchor("用户", List.of("User"),
            "锚点实体：用户本人的抽象。大模型通过维护本实体与其它实体的关系"
                    + "（偏好/属性/经历/人际），在图谱上构建并持续修正用户画像。");

    /** 全部锚点（{@link AnchorSeeder} 逐条幂等播种；新增锚点在此登记即可） */
    public static final List<Anchor> ALL = List.of(USER);

    /** 该实体是否锚点（types 含标记即算） */
    public static boolean isAnchor(GraphStore.Entity entity) {
        return entity != null && entity.types() != null && entity.types().contains(TYPE);
    }

    private GraphAnchors() {
    }
}
