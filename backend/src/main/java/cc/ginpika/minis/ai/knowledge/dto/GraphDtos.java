package cc.ginpika.minis.ai.knowledge.dto;

import java.util.List;

/** 图谱维护 DTO：实体 / 关系的 upsert 请求与结果（管理端写图）。 */
public final class GraphDtos {

    public record EntityRequest(String type, String name, List<String> aliases, String summary) {
    }

    public record EntityResult(String id, String type, String name) {
    }

    public record RelationRequest(String subject, String predicate, String object) {
    }

    public record RelationResult(String id, String subject, String predicate, String object) {
    }

    private GraphDtos() {
    }
}
