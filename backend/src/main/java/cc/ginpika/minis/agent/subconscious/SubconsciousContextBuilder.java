package cc.ginpika.minis.agent.subconscious;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import cc.ginpika.minis.agent.subconscious.store.PlanStore;
import org.springframework.stereotype.Component;

/**
 * 把「提示词正文 + 注入器绑定 + 本轮交代」当场组装成一条完整的 LLM 上下文。
 *
 * <p>两类调用方共用这一条组装路径：心跳主会话（{@link #build(String, String)}，只有正文与绑定）
 * 与任务计划（{@link #build(PlanStore.PlanRow)}，另有角色与「这次做什么」的 input）。
 *
 * <p>子代理不走这里：它没有正文与插槽绑定，只有派它的人写的那段交代
 * （见 {@code SubagentService}），system 是固定的内置人格。
 *
 * <p>这是「状态化构造」的落点：正文与绑定都写在自己身上，每次执行都重新向
 * {@link SubconsciousInjectors} 索要素材、重新填槽、重新序列化。
 * 组装结果会原样交给调用方落进 {@code sub_exec.context}，于是
 * 「当时到底给模型看了什么」永远可回溯——LLM 那一步本身则完全无状态。
 */
@Component
public class SubconsciousContextBuilder {

    /** 插槽的一个绑定：从哪个注入器、带什么参数取素材 */
    private record Binding(String slot, String injector, String args) {
    }

    /** 组装结果：messages 数组 + 其序列化文本（落库用） */
    public record Built(ArrayNode messages, String json) {
    }

    private final ObjectMapper om;
    private final SubconsciousInjectors injectors;

    public SubconsciousContextBuilder(ObjectMapper om, SubconsciousInjectors injectors) {
        this.om = om;
        this.injectors = injectors;
    }

    /**
     * 组装一次计划执行的全部 messages。
     *
     * <p>计划自己的 role 决定第一条消息的角色（system/user）；它的 input 作为「本次的具体交代」
     * 追加在后——正文管「你是谁、怎么做事」，input 管「这一轮做什么」。
     */
    public Built build(PlanStore.PlanRow plan) {
        return assemble(plan.role(), plan.content(), plan.injectors(), plan.input(), plan);
    }

    /**
     * 组装心跳主会话（总线）的上下文：它只有一份正文与自己的插槽绑定——正文即它的全部人格与职责，
     * 恒为 system，没有「本次交代」这一层（每拍要做什么由它自己看着名册决定）。
     */
    public Built build(String content, String injectors) {
        return assemble("system", content, injectors, null, null);
    }

    private Built assemble(String role, String content, String injectors, String input,
                           PlanStore.PlanRow plan) {
        Map<String, Binding> bindings = bindings(injectors);

        String body = render(content, bindings, plan, true);
        String seed = render(input, bindings, plan, false);

        ArrayNode messages = om.createArrayNode();
        if ("user".equalsIgnoreCase(role)) {
            messages.add(message("user", seed.isBlank() ? body : body + "\n\n" + seed));
        } else {
            messages.add(message("system", body));
            if (!seed.isBlank()) {
                messages.add(message("user", seed));
            }
        }
        return new Built(messages, messages.toString());
    }

    /** 只渲染文本（预览用） */
    public String render(PlanStore.PlanRow plan) {
        return render(plan.content(), bindings(plan.injectors()), plan, true);
    }

    // ---------- 填槽 ----------

    /**
     * 用绑定的注入器填充 {@code {{插槽}}}。
     *
     * @param allowPlanInput 是否允许 {@code plan_input} 注入器生效——渲染 input 自身时必须关掉，
     *                       否则会自己引用自己
     */
    private String render(String template, Map<String, Binding> bindings,
                          PlanStore.PlanRow plan, boolean allowPlanInput) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Matcher m = SubconsciousPrompts.SLOT.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String slot = m.group(1);
            String material = material(slot, bindings.get(slot), plan, allowPlanInput);
            m.appendReplacement(out, Matcher.quoteReplacement(
                    material.isBlank() ? SubconsciousPrompts.SLOT_MISSING : material));
        }
        m.appendTail(out);
        return out.toString();
    }

    private String material(String slot, Binding binding, PlanStore.PlanRow plan,
                            boolean allowPlanInput) {
        if (binding == null) {
            return "";
        }
        if (!allowPlanInput && SubconsciousInjectors.PLAN_INPUT.equals(binding.injector())) {
            return "";
        }
        return injectors.load(slot, binding.injector(), binding.args(), plan);
    }

    /** 解析 injectors JSON（[{slot, injector, args}]）；坏数据退化为空绑定而非抛错 */
    private Map<String, Binding> bindings(String json) {
        Map<String, Binding> map = new LinkedHashMap<>();
        for (JsonNode node : readArray(json)) {
            String slot = node.path("slot").asText("");
            String injector = node.path("injector").asText("");
            if (!slot.isBlank() && !injector.isBlank()) {
                map.put(slot, new Binding(slot, injector, node.path("args").asText("")));
            }
        }
        return map;
    }

    private ArrayNode readArray(String json) {
        if (json == null || json.isBlank()) {
            return om.createArrayNode();
        }
        try {
            JsonNode node = om.readTree(json);
            return node.isArray() ? (ArrayNode) node : om.createArrayNode();
        } catch (Exception e) {
            return om.createArrayNode();
        }
    }

    private ObjectNode message(String role, String content) {
        ObjectNode node = om.createObjectNode();
        node.put("role", role);
        node.put("content", content);
        return node;
    }
}
