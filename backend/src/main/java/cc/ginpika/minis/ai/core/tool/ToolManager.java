package cc.ginpika.minis.ai.core.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 工具管理器：把散落在各工具类里的工具定义汇总成一次 LLM 调用所需的 tools 数组。
 *
 * <p>它只认两件事：工具归属哪些工具面（{@link AiTool#surfaces()}）与是否写库（{@link AiTool#writes()}）。
 * 新增一个工具＝新增一个 {@link AiTool} 实现类，本类无需任何改动——于是「工具的清单」永远等于
 * 「已存在的工具类」，不会出现登记遗漏或登记不一致。
 */
@Component
public class ToolManager {

    private final List<AiTool> tools;
    private final ObjectMapper om;

    public ToolManager(List<AiTool> tools, ObjectMapper om) {
        /* 按名字排序：Spring 注入的 List 顺序不保证稳定，固定顺序让请求体可复现、便于比对 */
        this.tools = tools.stream().sorted(Comparator.comparing(AiTool::name)).toList();
        this.om = om;
    }

    /** 全部已登记的工具（构造时已按名字排序）：工具清单页只读展示 */
    public List<AiTool> allTools() {
        return tools;
    }

    /** 当前已启用且属于指定运行场景的工具；主 Agent 与能力页共用此清单。 */
    public List<AiTool> availableTools(ToolSurface surface) {
        return tools.stream().filter(t -> t.available() && t.surfaces().contains(surface)).toList();
    }

    /** 某工具面的全部工具定义（无则返回空数组） */
    public ArrayNode toolsOf(ToolSurface surface) {
        ArrayNode out = om.createArrayNode();
        for (AiTool tool : tools) {
            if (tool.available() && tool.surfaces().contains(surface)) {
                out.add(tool.schema(om));
            }
        }
        return out;
    }

    /**
     * 某工具面上、落在给定能力域集合内的工具定义（分层路由的落点：先选域，再注入该域）。
     *
     * <p>与 {@link #toolsOf(ToolSurface)} 一样无视 {@link AiTool#agentsMayCall()}——那是「谁能自动拿到」
     * 的关卡，由调用方另行把关，这里只负责「域选中了就取定义」。空集返回空数组。
     */
    public ArrayNode toolsOfDomains(Set<ToolDomain> domains, ToolSurface surface) {
        ArrayNode out = om.createArrayNode();
        if (domains == null || domains.isEmpty()) {
            return out;
        }
        for (AiTool tool : tools) {
            if (tool.available() && tool.surfaces().contains(surface) && domains.contains(tool.domain())) {
                out.add(tool.schema(om));
            }
        }
        return out;
    }

    /** 按名字找工具定义：鉴权按名字反查它的能力域与风险等级（见 {@code CapabilityGuard}） */
    public Optional<AiTool> find(String name) {
        return tools.stream().filter(t -> t.name().equals(name)).findFirst();
    }

    /**
     * 按名字挑出某个工具面上的工具定义（任务计划与子代理按自己声明的名字取工具用）。
     *
     * <p>只认「worker 能调的」（{@link AiTool#agentsMayCall()}）且落在给定工具面上的：
     * 声明里就算写了 create_heartbeat 这类「管编制 / 翻别人过程」的工具也拿不到，
     * 写了会话工具（open_thread / send_message，属 THREAD 面）也拿不到——会话渠道由
     * {@code channels} 单独授权，不由工具名声明。
     */
    public ArrayNode toolsOf(List<String> names, ToolSurface surface) {
        ArrayNode out = om.createArrayNode();
        if (names == null || names.isEmpty()) {
            return out;
        }
        for (AiTool tool : tools) {
            if (tool.available() && tool.agentsMayCall() && tool.surfaces().contains(surface)
                    && names.contains(tool.name())) {
                out.add(tool.schema(om));
            }
        }
        return out;
    }

    /**
     * 按名字挑出某个工具面上的工具定义，<b>但不过 {@link AiTool#agentsMayCall()} 这一关</b>。
     *
     * <p>给「人显式授权」的场景用：{@link #toolsOf(List, ToolSurface)} 会挡掉
     * {@code run_command} / {@code list_terminals} 这类「不给子代理自动拿」的工具，
     * 而成员页上由人亲手勾选、或绑定了工作空间才授予的终端工具，正该走得通。
     *
     * <p>授权面由调用方把关（谁能拿到哪些名字），这里只负责取定义。
     */
    public ArrayNode grantedToolsOf(List<String> names, ToolSurface surface) {
        ArrayNode out = om.createArrayNode();
        if (names == null || names.isEmpty()) {
            return out;
        }
        for (AiTool tool : tools) {
            if (tool.available() && tool.surfaces().contains(surface) && names.contains(tool.name())) {
                out.add(tool.schema(om));
            }
        }
        return out;
    }

    /**
     * 某个工具面上全部可由 agent 调用的工具定义：多智能体成员统一拿到这一份。
     *
     * <p>工具不再逐成员授权；{@link AiTool#agentsMayCall()} 只用于挡掉「不该自动交给 agent」的
     * 管理类工具，其余全部开放。终端工具仍由工作空间绑定单独授予，不在本清单里。
     */
    public ArrayNode agentToolsOf(ToolSurface surface) {
        ArrayNode out = om.createArrayNode();
        for (AiTool tool : tools) {
            if (tool.available() && tool.surfaces().contains(surface) && tool.agentsMayCall()) {
                out.add(tool.schema(om));
            }
        }
        return out;
    }

    /** 是否属于会写知识库（文档 / 图谱）的工具：调用后应记入写库清单 */
    public boolean isWriteTool(String name) {
        return tools.stream().anyMatch(t -> t.writes() && t.name().equals(name));
    }

    /** 该工具声明可并行执行吗（见 {@link AiTool#parallelizable()}）；未登记的工具按不可并行算 */
    public boolean isParallelizable(String name) {
        return tools.stream().anyMatch(t -> t.parallelizable() && t.name().equals(name));
    }

    /** Default read-only capability: task/command side effects are excluded even when they do not write knowledge. */
    public ArrayNode readOnlyToolsOf(ToolSurface surface) {
        ArrayNode out = om.createArrayNode();
        for (AiTool tool : tools) {
            if (tool.available() && tool.surfaces().contains(surface) && tool.risk() == ToolRisk.READ && tool.agentsMayCall()) {
                out.add(tool.schema(om));
            }
        }
        return out;
    }
}
