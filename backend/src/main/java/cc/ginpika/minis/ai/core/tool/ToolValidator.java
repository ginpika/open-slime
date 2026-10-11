package cc.ginpika.minis.ai.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 校验节点（STEP 4）：写入 / 执行类工具在<b>落地之前</b>过一道自检，拒了就回一句可读理由、不落执行。
 *
 * <p>与 {@code CapabilityGuard} 的分工：
 * <ul>
 *   <li>{@code CapabilityGuard} 判「有没有权限」——域是否授权、只读授权下要不要挡写入 / 执行；</li>
 *   <li>本类判「这一次的参数能不能落地」——命令为空、要删的对象没给 id、正文与标题皆空……</li>
 * </ul>
 * 两者串在执行的同一条链上：先鉴权、再自检，都过了才分发到业务侧。自检只做<b>零成本</b>的参数判断，
 * 绝不在这里再调一轮模型——架构文档 STEP 4 明确「只给写入 / 执行插校验，不给所有回答统一加自检」。
 *
 * <p>只读工具直接放行：检索类没有副作用，挡它只是白白多一次往返。未登记的工具名同样放行
 * （交给执行侧按「未知工具」如实回绝）。
 */
@Component
public class ToolValidator {

    private final ToolManager tools;
    private final ObjectMapper om;

    public ToolValidator(ToolManager tools, ObjectMapper om) {
        this.tools = tools;
        this.om = om;
    }

    /**
     * 复核一次工具调用是否可落地。
     *
     * @param toolName 模型请求调用的工具名
     * @param argsJson 调用参数原文（JSON 字符串；空 / 坏数据按空对象处理）
     * @return 拒绝理由（可读，直接作为 tool 结果回给模型）；放行返回 null
     */
    public String validate(String toolName, String argsJson) {
        Optional<AiTool> found = tools.find(toolName == null ? "" : toolName);
        if (found.isEmpty()) {
            return null;
        }
        AiTool tool = found.get();
        /* 只读工具无副作用，不出自检；写入 / 执行才校验参数 */
        if (tool.risk() == ToolRisk.READ) {
            return null;
        }
        return tool.validate(read(argsJson));
    }

    /** 参数原文 → JsonNode；空 / 解析失败退化为空对象（工具的自检须自行容错缺失字段） */
    private JsonNode read(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return om.createObjectNode();
        }
        try {
            return om.readTree(argsJson);
        } catch (Exception e) {
            return om.createObjectNode();
        }
    }
}
