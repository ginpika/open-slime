package cc.ginpika.minis.ai.core.tool.thread;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * open_thread：在首页 chat 里开（或按名字复用）一个会话，子代理随后用 {@link SendMessageTool} 往里投消息。
 *
 * <p>会话按名字复用：同名即同一个会话（无则新建），跨子代理共享、持续累积
 * （如「记忆体检」随每次心跳追加报告）；投递后该会话在首页「最近会话」里打未读点。
 */
@Component
public class OpenThreadTool implements AiTool {

    public static final String NAME = "open_thread";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "在首页 chat 里开一个会话（按名字复用：已有同名会话就直接返回它，无则新建；跨子代理共享并持续累积）。"
                + "当你有话要说给人听时，先开/取一个会话，再用 " + SendMessageTool.NAME + " 往里投消息；"
                + "只想留痕、不必打扰人时不要调用。";
    }

    @Override
    public Set<ToolSurface> surfaces() {
        return Set.of(ToolSurface.THREAD);
    }

    @Override
    public ObjectNode parameters(ObjectMapper om) {
        ObjectNode params = om.createObjectNode();
        params.put("type", "object");
        params.putObject("properties").putObject("title")
                .put("type", "string")
                .put("description", "会话名（即标识）：同一主题请始终用同一个名字，如「记忆体检」");
        params.putArray("required").add("title");
        return params;
    }
}
