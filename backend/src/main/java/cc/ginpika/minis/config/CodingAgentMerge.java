package cc.ginpika.minis.config;

import cc.ginpika.minis.ai.core.BuiltinAgents;
import cc.ginpika.minis.ai.core.prompt.skill.CodingSkill;
import cc.ginpika.minis.ai.core.prompt.store.PromptStore;
import cc.ginpika.minis.agent.member.AgentPromptStore;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Data-only v6 migration. Original directory/profile rows remain archived for recovery. */
final class CodingAgentMerge {
    private final JdbcTemplate jdbc;
    CodingAgentMerge(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    void apply() {
        preservePrompts();
        var claims = jdbc.queryForList("SELECT id FROM graph_claim WHERE agent_id=?", Long.class, BuiltinAgents.CODING);
        for (String table : new String[]{"task", "coding_run_agent", "hub_session", "graph_claim", "memory_event", "memory_edge", "memory_work", "project_proposal"}) {
            // Some memory-only installations/tests do not initialize the optional IDE schema.
            if (jdbc.queryForObject("SELECT count(*) FROM sqlite_master WHERE type='table' AND name=?", Integer.class, table) > 0) {
                jdbc.update("UPDATE " + table + " SET agent_id=? WHERE agent_id=?", BuiltinAgents.CORE, BuiltinAgents.CODING);
            }
        }
        jdbc.update("UPDATE hub_session SET member_ids=replace(member_ids,?,?) WHERE member_ids IS NOT NULL",
                "\"" + BuiltinAgents.CODING + "\"", "\"" + BuiltinAgents.CORE + "\"");
        mergeCollaboration();
        var graph = new cc.ginpika.minis.ai.memory.graph.MemoryGraphStore(jdbc);
        var memories = graph.claims();
        for (long id : claims) {
            var item = memories.findById(id).orElseThrow();
            String fingerprint = cc.ginpika.minis.ai.memory.MemoryVocabulary.fingerprint(item.owner(), item.projectId(),
                    item.agentId(), item.domain(), item.scope(), item.body(), item.layer(), item.sessionId(), item.taskId());
            var duplicate = memories.findByFingerprint(fingerprint).orElse(null);
            if (duplicate != null && duplicate.id() != id) jdbc.update("UPDATE graph_claim SET status='archived' WHERE id=?", id);
            jdbc.update("UPDATE graph_claim SET fingerprint=? WHERE id=?", fingerprint, id);
            jdbc.update("UPDATE memory_revocation SET fingerprint=? WHERE fingerprint=?", fingerprint, item.fingerprint());
            graph.bind(id);
        }
    }

    private void mergeCollaboration() {
        // Keep the original configuration rows as archives, while adding the canonical membership/grant.
        jdbc.update("INSERT OR IGNORE INTO collaboration_project_member(project_id,agent_id,position) "
                + "SELECT project_id,?,position FROM collaboration_project_member WHERE agent_id=?", BuiltinAgents.CORE, BuiltinAgents.CODING);
        jdbc.update("INSERT OR IGNORE INTO session_continuation(target_session_id,source_session_id,agent_id,created_at,status) "
                + "SELECT target_session_id,source_session_id,?,created_at,status FROM session_continuation WHERE agent_id=?", BuiltinAgents.CORE, BuiltinAgents.CODING);
        for (String column : new String[]{"owner_peer", "block_with"}) replace("roundtable_todo", column);
        for (String column : new String[]{"coordinator_id", "executor_id", "reviewer_id"}) replace("collaboration_plan", column);
        for (String table : new String[]{"collaboration_assignment", "collaboration_coding_run"}) {
            replace(table, "executor_id"); replace(table, "reviewer_id");
        }
        // A merge must never turn an independent review into self-approval. Old assignments remain as history.
        jdbc.update("UPDATE collaboration_plan SET phase='planning',version=version+1,todo_id=NULL,executor_id=NULL,reviewer_id=NULL, "
                + "decision='默认 Agent 已合并编程身份，请重新指定独立检查者。' WHERE phase IN ('executing','checking') AND "
                + "(executor_id=reviewer_id OR session_id IN (SELECT session_id FROM collaboration_assignment WHERE executor_id=reviewer_id AND phase IN ('executing','checking')))");
    }

    private void replace(String table, String column) {
        jdbc.update("UPDATE " + table + " SET " + column + "=? WHERE " + column + "=?", BuiltinAgents.CORE, BuiltinAgents.CODING);
    }

    private void preservePrompts() {
        var legacy = new AgentPromptStore(jdbc);
        var global = new PromptStore(jdbc);
        var bindings = legacy.bindings(BuiltinAgents.CODING);
        String activeIdentity = legacy.binding(BuiltinAgents.CODING, "identity").orElse(null);
        StringBuilder extras = new StringBuilder();
        // Preserve active custom guidance/terminal instructions along with the identity versions.
        for (var binding : bindings) {
            if (!java.util.Set.of("chat_guidance", "terminal").contains(binding.promptKey()) || binding.versionId() == null) continue;
            legacy.version(binding.versionId()).filter(v -> BuiltinAgents.CODING.equals(v.peerId()) && binding.promptKey().equals(v.promptKey()))
                    .ifPresent(v -> extras.append("\n\n").append(v.content()));
        }
        String selected = null;
        for (var version : legacy.versions(BuiltinAgents.CODING)) {
            if (!"identity".equals(version.promptKey())) continue;
            String id = "coding-skill-legacy-" + version.id();
            if (global.version(id).isEmpty()) {
                global.insertVersion(new PromptStore.VersionRow(id, CodingSkill.KEY,
                        "原编程配置 · " + version.name(), content(version.content() + extras), version.updatedAt()));
            }
            if (Objects.equals(version.id(), activeIdentity)) selected = id;
        }
        if (selected == null && !extras.isEmpty()) {
            selected = "coding-skill-legacy-active";
            if (global.version(selected).isEmpty()) global.insertVersion(new PromptStore.VersionRow(
                    selected, CodingSkill.KEY, "原编程配置", content(extras.toString()), 0));
        }
        // An explicit new Skill binding, including a reset to builtin, always wins.
        boolean configured = global.bindings().stream().anyMatch(b -> CodingSkill.KEY.equals(b.promptKey()));
        if (!configured && selected != null) global.saveBinding(CodingSkill.KEY, selected);
    }

    private static String content(String legacy) {
        return "原编程状态的配置（仅作为 IDE 工作规程保留，身份与模型以默认 Agent 为准）：\n"
                + legacy + "\n\n" + CodingSkill.DEFAULT;
    }
}
