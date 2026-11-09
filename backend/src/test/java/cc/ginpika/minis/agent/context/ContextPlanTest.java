package cc.ginpika.minis.agent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.ginpika.minis.agent.context.ContextFragment.Layer;
import cc.ginpika.minis.agent.context.ContextFragment.Ref;
import cc.ginpika.minis.agent.context.ContextFragment.Trust;
import cc.ginpika.minis.ai.core.tool.ToolOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ContextPlanTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int RESERVE = 1000;
    private static final int SAFETY = 1024;
    private static final ContextPlan.Scope SCOPE = new ContextPlan.Scope("session", "agent", null);

    @Test
    void zeroRemainingHistoryKeepsOnlyRequiredInputAndInstructions() {
        ContextFragment instructions = ContextFragment.system(Layer.INSTRUCTION, "system_base",
                Trust.INSTRUCTION, "required instructions");
        ContextFragment current = turn(Layer.TASK, 21, "current input");
        int required = requiredWindow(instructions, current);
        var plan = plan(required, instructions, turn(Layer.WORK, 20, "historical input"), current);

        var fitted = plan.fit(plan.body());
        assertEquals(2, fitted.body().path("messages").size());
        assertEquals("current input", fitted.body().path("messages").path(1).path("content").asText());
        assertEquals(3, plan.body().path("messages").size());
        assertEquals(1, fitted.selection().omittedCount());
        assertEquals(List.of("work_over_budget"), fitted.selection().omitted().getFirst().reasons());
        assertEquals(21, fitted.selection().historyWatermark());
        assertEquals(required, fitted.selection().estimatedInputTokens() + RESERVE + SAFETY);
    }

    @Test
    void failsWithExplicitBudgetWhenRequiredInputIsOneTokenTooLarge() {
        ContextFragment current = turn(Layer.TASK, 10, "用户当前约束".repeat(200));
        int required = requiredWindow(current);
        var plan = plan(required - 1, current);

        var failure = assertThrows(ResponseStatusException.class, () -> plan.fit(plan.body()));
        assertEquals(400, failure.getStatusCode().value());
        assertTrue(failure.getReason().contains("上下文预算不足"));
        assertTrue(failure.getReason().contains("模型窗口 " + (required - 1)));
    }

    @Test
    void countsDynamicSuffixAndOptionalMediaBeforeSending() {
        ContextFragment suffix = ContextFragment.system(Layer.WORK, "active_subagents",
                Trust.AUTHORITATIVE_STATE, "动态片段".repeat(2000));
        ContextFragment current = turn(Layer.TASK, 8, "继续排查");
        var plan = plan(4500, suffix, current);
        var messages = (ArrayNode) plan.body().path("messages");
        ObjectNode assistant = messages.addObject().put("role", "assistant");
        assistant.putArray("tool_calls").addObject().put("id", "inspect")
                .putObject("function").put("name", "inspect").put("arguments", "{}");
        messages.addObject().put("role", "tool").put("tool_call_id", "inspect").put("content", "inspection");
        messages.add(ToolOutput.observation(JSON, "inspect", List.of("data:image/png;base64,example")));

        var fitted = plan.fit(plan.body());
        assertFalse(fitted.body().toString().contains("动态片段"));
        assertFalse(fitted.body().toString().contains("data:image"));
        assertTrue(plan.body().toString().contains("data:image"));
        assertEquals("inspect", fitted.body().path("messages").path(1).path("tool_calls").path(0).path("id").asText());
        assertEquals("inspect", fitted.body().path("messages").path(2).path("tool_call_id").asText());
        assertTrue(fitted.selection().selected().stream().anyMatch(
                entry -> entry.reasons().contains("live_tool_result_over_budget")));
        assertTrue(ContextPlan.estimateRequest(plan.body()) - fitted.selection().estimatedInputTokens() > 4096);
    }

    private static int requiredWindow(ContextFragment... fragments) {
        var baseline = plan(20_000, fragments);
        return Math.toIntExact(baseline.fit(baseline.body()).selection().estimatedInputTokens()) + RESERVE + SAFETY;
    }

    private static ContextPlan plan(int window, ContextFragment... fragments) {
        ObjectNode body = JSON.createObjectNode().put("model", "fixture");
        body.putArray("tools").addObject().putObject("function").put("name", "inspect")
                .putObject("parameters").put("type", "object");
        return new ContextPlan(body, List.of(fragments), SCOPE, window, RESERVE);
    }

    private static ContextFragment turn(Layer layer, long id, String content) {
        return new ContextFragment(layer, "history/USER_INPUT", Trust.TRANSCRIPT,
                List.of(new Ref("user", "message:" + id)),
                List.of(JSON.createObjectNode().put("role", "user").put("content", content)));
    }
}
