package cc.ginpika.minis.ai.knowledge.dto;

import java.util.List;

/** 知识灌入 DTO：把一段文本切块入库（可选向量化），并可按 mentions 顺带建实体。 */
public final class IngestDtos {

    /** mentions=正文中要顺带建实体并关联到各 chunk 的实体名；source/sourceRef 缺省 note/自增 */
    public record IngestRequest(String title, String source, String sourceRef,
                                String content, List<String> tags, List<String> mentions) {
    }

    /** chunks=切出的块数；embedded=是否至少有一块成功写入向量 */
    public record IngestResult(int chunks, boolean embedded) {
    }

    private IngestDtos() {
    }
}
