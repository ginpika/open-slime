package cc.ginpika.minis.ai.core.prompt.block;

import cc.ginpika.minis.ai.core.prompt.PromptBlock;
import cc.ginpika.minis.ai.core.prompt.PromptLibrary;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 确认入库（function calling 写图谱） */
@Component
public class CommitBlock implements PromptBlock {

    public static final String KEY = "commit";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String name() {
        return "确认入库";
    }

    @Override
    public String description() {
        return "逐条调用 upsert_node / upsert_edge 写入图谱，并简述写入了多少节点与关系。";
    }

    @Override
    public Set<String> contexts() {
        return Set.of(PromptLibrary.CTX_COMMIT);
    }

    @Override
    public String builtin() {
        return """
                你是知识图谱写入助手。用户会给出三元组清单，请逐条调用工具写入图谱：
                - 每条三元组先 upsert_node 写入两端实体（type 缺省 CONCEPT，可为实体补一句 summary），
                  再 upsert_edge 写入关系；
                - 相同名实体只写一次；全部写完后停止调用工具，并简述写入了多少节点与关系。""";
    }
}
