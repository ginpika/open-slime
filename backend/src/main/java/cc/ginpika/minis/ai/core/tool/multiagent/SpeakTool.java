package cc.ginpika.minis.ai.core.tool.multiagent;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * speak：在圆桌上开口说话——「发言」这个动作本身。
 *
 * <p><b>为什么发言要是一个工具</b>：原生协议下，模型一次输出里能同时给出思考（reasoning）与工具调用，
 * 而正文只有一份、且只在「不再调工具」的那一轮才算数。于是「说话」与「继续思考 / 查资料」在协议上
 * 是互斥的：要么只说、要么只做。做成工具之后这个矛盾就没了——说一句是一个工具调用，说完那一轮
 * 照常继续工具往返，模型可以边说边想、说几句、查个资料再接着说，与人说话的样子一致。
 *
 * <p>一次发言里可以调用任意多次，{@code RoundtableChatService} 按调用顺序把每句当场推给页面并累积成正文；
 * 它不改状态机、不写库，只把话说出去（表态仍由 propose_end / go_silent / interject / ask_user 给出）。
 *
 * <p><b>刻意不声明可并行</b>（{@link AiTool#parallelizable()} 默认 false）：发言要严格按调用顺序落地，
 * 且落地处依赖「此刻在替谁发言」的线程局部变量——{@code ToolBatch} 只要见到一个不可并行的工具
 * 就会让整批退回串行，顺序与落点因此都对得上。
 */
@Component
public class SpeakTool implements AiTool {

    public static final String NAME = "speak";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "在圆桌上开口说话：调用的一瞬间，text 里的这句话就被桌上所有人听到了——这不是「记录」，"
                + "而是你真的开口说了这句话。一次发言里可以调用任意多次，按调用顺序依次说出去；"
                + "中间可以继续思考、继续调用检索工具查证，然后再接着说，像人一样边想边说。"
                + "要说的每一句都走这里，不要写进正文（在圆桌上，正文里写的字不算发言）。"
                + "话说完之后，再用一次状态工具（propose_end / go_silent / interject / ask_user）"
                + "表明你的去向。";
    }

    @Override
    public Set<ToolSurface> surfaces() {
        return Set.of(ToolSurface.PEER);
    }

    @Override
    public ObjectNode parameters(ObjectMapper om) {
        ObjectNode params = om.createObjectNode();
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");
        props.putObject("text").put("type", "string")
                .put("description", "你要说出口的那句话：口语，原样说出（一句话或一小段都行，不要写旁白与排版）");
        params.putArray("required").add("text");
        return params;
    }
}
