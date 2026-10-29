package cc.ginpika.minis.ai.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;

/**
 * 一个工具：OpenAI 兼容 function calling 里的单条工具定义。
 *
 * <p>每个工具单独成类——工具是开发者亲手写下的编程契约（名字、描述、参数 schema、可出现的工具面），
 * 各自独立演进、互不干扰；由 {@link ToolManager} 汇总成各工具面的 tools 数组。
 *
 * <p>这里只表达「工具长什么样」：工具调用的<b>执行</b>不在这里，LLM 看到的是定义，
 * 调用落地到业务仍由各业务服务负责（见 {@code KnowledgeToolbox} / {@code AnalyzeService}）。
 */
public interface AiTool {

    /** 工具名：模型据此发起调用，须全局唯一，并与执行侧的分发名一致 */
    String name();

    /** 工具描述：模型据此决定何时调用（可引用其它工具的 {@link #name()}，用方法而非静态字段以避开初始化环） */
    String description();

    /** Runtime configuration gate; execution must enforce the same gate independently. */
    default boolean available() { return true; }

    /** 该工具出现在哪些工具面 */
    Set<ToolSurface> surfaces();

    /**
     * 业务领域：成员页「可用工具」按它归类分 Tab（默认「其他」）。
     *
     * <p>纯展示维度，不参与调用——工具面决定模型能看见什么，领域只决定页面上归到哪一类。
     */
    default ToolDomain domain() {
        return ToolDomain.GENERAL;
    }

    /** 是否写知识库：调用后应记入写库清单（默认只读） */
    default boolean writes() {
        return false;
    }

    /**
     * 风险等级（能力平面 STEP 3）：只读 / 写入 / 执行。
     *
     * <p>默认由 {@link #writes()} 推出——写库即 {@link ToolRisk#WRITE}，其余 {@link ToolRisk#READ}；
     * 「执行」这一档高出写入一截（命令能产生任意副作用），必须由工具显式声明（如 {@code run_command}）。
     * 授权与沙箱据此放行或拒绝（见 {@code CapabilityGuard}）。
     */
    default ToolRisk risk() {
        return writes() ? ToolRisk.WRITE : ToolRisk.READ;
    }

    /**
     * 子代理能否调用本工具（默认能）。
     *
     * <p>只有「管子代理本身的工具」为 false——派活、翻过程、创建/改写/删除子代理都属于大模型自己
     * 的那一层：子代理是干活的，不该再拉起、审视或改写自己的同类（否则一层套一层，名册失控）。
     */
    default boolean agentsMayCall() {
        return true;
    }

    /**
     * 多个本工具的调用同时到达时能否并行执行（默认不能）。
     *
     * <p>默认为 false 是因为多数工具有顺序语义（先写后读、改了再查），并起来会把隐含依赖打乱；
     * 只有「彼此无关、纯等待上游」的工具才声明 true（如 {@code delegate_subagent}——
     * 一条消息里派的几个子代理互不相干，谁先谁后都不影响结果）。
     */
    default boolean parallelizable() {
        return false;
    }

    /**
     * 调用前的自检（STEP 4 校验节点）：这个工具「现在这次调用」是否可执行，不可执行就回一句可读理由。
     *
     * <p>与 {@link #risk()} 的分工：风险等级说「这类工具能把世界改到什么程度」（静态属性，决定要不要授权），
     * 自检说「这一次的参数能不能落地」（动态判断，如命令为空、要删的对象没给 id）。默认放行（返回 null）——
     * 只有写入 / 执行类工具才需要覆写它，且应当只做零成本的参数自检，绝不在这里调模型
     * （架构文档 STEP 4：只给写入 / 执行插校验，不给所有回答统一加一轮自检）。
     *
     * <p>由 {@code ToolValidator} 在写入 / 执行前调用：拒了就当一条 tool 结果回给模型，不落执行。
     *
     * @param args 本次调用的参数（可能为空对象 / 字段缺失，实现须自行容错）
     * @return 拒绝理由（可读）；放行返回 null
     */
    default String validate(JsonNode args) {
        return null;
    }

    /** parameters 对象 schema（type=object + properties + required） */
    ObjectNode parameters(ObjectMapper om);

    /** 完整的一条工具定义：{type:"function", function:{name, description, parameters}} */
    default ObjectNode schema(ObjectMapper om) {
        ObjectNode tool = om.createObjectNode();
        tool.put("type", "function");
        ObjectNode fn = tool.putObject("function");
        fn.put("name", name());
        fn.put("description", description());
        fn.set("parameters", parameters(om));
        return tool;
    }

    /** 字符串数组字段 schema（如 remove_keys / aliases） */
    static void putStringArraySchema(ObjectNode parent, String field, String desc) {
        ObjectNode arr = parent.putObject(field);
        arr.put("type", "array");
        arr.put("description", desc);
        arr.putObject("items").put("type", "string");
    }

    /** 取值受限的字符串数组字段 schema（如交付渠道），values 列出全部合法取值 */
    static void putEnumArraySchema(ObjectNode parent, String field, String desc, String... values) {
        ObjectNode arr = parent.putObject(field);
        arr.put("type", "array");
        arr.put("description", desc);
        ObjectNode items = arr.putObject("items");
        items.put("type", "string");
        ArrayNode enums = items.putArray("enum");
        for (String v : values) {
            enums.add(v);
        }
    }

    /** 三元组 item 的属性数组 schema：[{k, v, t}]，t 为 text|number|url|date|bool */
    static void putPropArraySchema(ObjectNode parent, String field, String desc) {
        ObjectNode arr = parent.putObject(field);
        arr.put("type", "array");
        arr.put("description", desc);
        ObjectNode entry = arr.putObject("items");
        entry.put("type", "object");
        ObjectNode ep = entry.putObject("properties");
        ep.putObject("k").put("type", "string").put("description", "属性名，如 学号");
        ep.putObject("v").put("type", "string").put("description", "属性值，如 38号");
        ep.putObject("t").put("type", "string")
                .put("description", "值类型：text|number|url|date|bool（缺省 text）");
        entry.putArray("required").add("k").add("v");
    }
}
