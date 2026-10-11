package cc.ginpika.minis.ai.core.tool.graph;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolDomain;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * read_my_anchor：读取「你自己」的图谱人格锚点——锚点实体 + 挂在它上面的一跳关系子图。
 *
 * <p>与系统预置的「用户」锚点是同一套机制：圆桌上的每位成员都可以在成员页建立一个人格锚点
 * （{@code agent.anchor_id}），它承载这位智能体自己的第一人称坐标（人设、知识、立场都挂在
 * 这个圆心周围）。本工具让成员在发言时随时回看这个坐标，据此校正自己的言行。
 *
 * <p><b>为什么 agentsMayCall=false 且只落在 CHAT / HEARTBEAT 面</b>：锚点是「成员 / 某位 Agent」的概念——
 * 首页对话没有它，子代理也不该拿着别人的坐标。置 false 使它不进 {@code readOnlyToolsOf}（成员 / 子代理的默认只读
 * 工具集）与成员页候选清单（{@code grantableTools}），只由 {@code RoundtableChatService} 在成员发言时、
 * 或 {@code HeartbeatService} 在某 Agent 心跳时显式授予。「自己」即当前发言成员 / 心跳 Agent，由编排层的
 * ThreadLocal 解出，故执行落在各自的分发里。
 */
@Component
public class ReadMyAnchorTool implements AiTool {

    public static final String NAME = "read_my_anchor";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "读取你自己的图谱人格锚点：锚点实体（类型 / 属性 / 摘要）+ 挂在它上面的一跳关系"
                + "（你与其它实体之间的「关系」，含属性与来源）。这是你在知识图谱里的第一人称坐标，"
                + "用来回顾、确认自己的人设与认知。无需参数。";
    }

    @Override
    public Set<ToolSurface> surfaces() {
        return Set.of(ToolSurface.CHAT, ToolSurface.HEARTBEAT);
    }

    @Override
    public ToolDomain domain() {
        return ToolDomain.GRAPH;
    }

    /** 锚点属于「当前发言的成员」：不给子代理自动拿（由 RoundtableChatService 在成员发言时显式授予） */
    @Override
    public boolean agentsMayCall() {
        return false;
    }

    @Override
    public ObjectNode parameters(ObjectMapper om) {
        ObjectNode params = om.createObjectNode();
        params.put("type", "object");
        params.putObject("properties");
        return params;
    }
}
