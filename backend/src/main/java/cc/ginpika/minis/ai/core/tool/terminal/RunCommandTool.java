package cc.ginpika.minis.ai.core.tool.terminal;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolDomain;
import cc.ginpika.minis.ai.core.tool.ToolRisk;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * run_command：在某个工作空间上执行一条命令行。
 *
 * <p>这是「Agent 直接控制所在环境」的唯一入口——本机 docker 容器与远程服务器（SSH）
 * 都是它的一个 target，模型不必知道底下是 docker 还是 ssh（见 {@code ai.terminal.ExecTarget}）。
 *
 * <p>刻意<b>不</b>给子代理与任务计划用（{@link #agentsMayCall()} 为 false）：匿名 worker 与常驻编制
 * 是自动跑起来的，它们手里不该出现任意命令执行权；要用命令行就由会话或心跳主会话当场决定。
 *
 * <p>默认不能并行（{@link AiTool#parallelizable()} 缺省 false）：命令之间有顺序语义，
 * 「先建目录再写文件」被并行打乱就成了随机结果。
 */
@Component
public class RunCommandTool implements AiTool {

    public static final String NAME = "run_command";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "在指定的工作空间上执行一条命令行，拿回退出码与 stdout/stderr。工作空间可以是本机 docker 容器，"
                + "也可以是远程服务器（SSH）——先用 " + ListTerminalsTool.NAME + " 看有哪些工作空间可用，"
                + "再用返回的名字作为 target。适合：查看环境状态与文件、跑构建与测试、查日志、"
                + "以及在用户明确要求时改动部署。"
                + "每条命令都是一次全新的非交互进程：工作目录与 shell 变量不跨调用保留，写下的文件与装上的包会保留，"
                + "要固定起点就用工作空间自带的工作目录或自己 cd。"
                + "某条命令超时会被真的终止，超长输出会按上限截断并标注；"
                + "若返回「未启用 / 被规则拒绝」，说明需要用户在工作空间页放行，不要反复重试同一条命令。"
                + "重要：命令是在真实环境里生效的，涉及删除、覆盖、重启、改权限这类不可逆操作前要先向用户确认。";
    }

    @Override
    public Set<ToolSurface> surfaces() {
        return Set.of(ToolSurface.CHAT, ToolSurface.HEARTBEAT);
    }

    @Override
    public ToolDomain domain() {
        return ToolDomain.TERMINAL;
    }

    /** 风险最高的一档：命令能产生任意副作用，授权与沙箱按 {@link ToolRisk#EXEC} 从严 */
    @Override
    public ToolRisk risk() {
        return ToolRisk.EXEC;
    }

    /** 子代理与任务计划都不给：任意命令执行权只留给「当场决定的那一层」 */
    @Override
    public boolean agentsMayCall() {
        return false;
    }

    /**
     * 校验节点（STEP 4）：命令 / 工作空间任一为空都当场拒——执行档风险最高，宁可少放行。
     * 这是零成本自检，真正的授权（目标是否启用、命令是否合规则）仍由 {@code TerminalService} 执行侧把关。
     */
    @Override
    public String validate(JsonNode args) {
        if (args.path("command").asText("").isBlank()) {
            return "command 为空：请给出要执行的命令行。";
        }
        if (args.path("target").asText("").isBlank()) {
            return "target 为空：请先用 " + ListTerminalsTool.NAME + " 查可用工作空间名，再指定 target。";
        }
        return null;
    }

    @Override
    public ObjectNode parameters(ObjectMapper om) {
        ObjectNode params = om.createObjectNode();
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");
        props.putObject("target").put("type", "string")
                .put("description", "工作空间名，取自 " + ListTerminalsTool.NAME + " 返回的清单");
        props.putObject("command").put("type", "string")
                .put("description", "要执行的命令行（交给目标环境的 sh -lc 执行，可用管道与重定向）");
        params.putArray("required").add("target").add("command");
        return params;
    }
}
