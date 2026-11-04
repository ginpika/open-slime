package cc.ginpika.minis.coding;

import cc.ginpika.minis.agent.member.*;
import cc.ginpika.minis.ai.core.BuiltinAgents;
import cc.ginpika.minis.ai.core.prompt.*;
import cc.ginpika.minis.ai.core.prompt.block.*;
import cc.ginpika.minis.ai.core.prompt.skill.CodingSkill;
import cc.ginpika.minis.ai.core.prompt.store.PromptStore;
import cc.ginpika.minis.coding.api.CodingDtos.*;
import cc.ginpika.minis.coding.config.CodingProps;
import cc.ginpika.minis.coding.context.CodingContext;
import cc.ginpika.minis.coding.store.CodingStore;
import cc.ginpika.minis.config.LlmProps;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CodingSkillTest {
    @Test void skillIsEditableAndOnlyComposedInIdeState() {
        var store = mock(PromptStore.class);
        var props = new LlmProps(); props.setSystemPrompt("shared-default-persona");
        var composer = new PromptComposer(store, props, List.of(new IdentityBlock(),
                new ChatGuidanceBlock(), new TerminalBlock(), new CodingSkill(),
                new SubagentBlock(), new SubagentSummaryBlock(), new CoordinationBlock()));
        var peers = mock(AgentStore.class);
        when(peers.peer(BuiltinAgents.CORE)).thenReturn(Optional.of(mock(AgentStore.AgentRow.class)));
        when(peers.peer("member")).thenReturn(Optional.of(mock(AgentStore.AgentRow.class)));
        var prompts = new AgentPromptService(mock(AgentPromptStore.class), composer,
                new PromptManager(composer, store), peers);

        assertTrue(prompts.view(BuiltinAgents.CORE).categories().stream().anyMatch(c -> PromptLibrary.CAT_SKILLS.equals(c.key())));
        assertFalse(prompts.view("member").categories().stream().anyMatch(c -> PromptLibrary.CAT_SKILLS.equals(c.key())));

        String chat = prompts.system(BuiltinAgents.CORE);
        assertTrue(chat.contains("shared-default-persona"));
        assertFalse(chat.contains("<coding_skill>"));
        assertFalse(chat.contains("PLAN 模式只读分析"));
        String ide = prompts.ideSystem();
        assertTrue(ide.contains("shared-default-persona"));
        assertTrue(ide.contains("<coding_skill>"));
        assertTrue(ide.contains("PLAN 模式只读分析"));

        when(store.bindings()).thenReturn(List.of(new PromptStore.BindingRow(CodingSkill.KEY, "custom")));
        when(store.version("custom")).thenReturn(Optional.of(new PromptStore.VersionRow(
                "custom", CodingSkill.KEY, "custom", "custom-ide-rules", 0)));
        String frozen = prompts.ideSystem();
        assertTrue(frozen.contains("custom-ide-rules"));
        assertFalse(frozen.contains("PLAN 模式只读分析"));
        assertFalse(prompts.system(BuiltinAgents.CORE).contains("custom-ide-rules"));

        var context = new CodingContext(new ObjectMapper(), null, mock(CodingStore.class), new CodingProps());
        var run = new Run("r", 1, "alice", "repo", "goal", null, Mode.EDIT, Runner.WORKSPACE,
                "QUEUED", 1, 0, null, null, "NOT_RUN", null, 0, 0);
        var repo = new Repository("repo", "alice", "project", "workspace://w/app", null, null, 0);
        assertTrue(context.system(run, repo, frozen).contains("custom-ide-rules"));
        assertFalse(context.system(run, repo, frozen).contains("PLAN 模式只读分析"),
                "A frozen override must not get a second builtin copy appended");
        assertTrue(context.system(run, repo, "legacy-profile").contains("PLAN 模式只读分析"),
                "Old checkpoints retain the rules previously appended by the runtime");

        when(store.version("custom")).thenReturn(Optional.of(new PromptStore.VersionRow(
                "custom", CodingSkill.KEY, "empty", "", 0)));
        assertFalse(context.system(run, repo, prompts.ideSystem()).contains("PLAN 模式只读分析"));
        assertTrue(frozen.contains("custom-ide-rules"), "Later edits do not mutate saved profiles");
    }
}
