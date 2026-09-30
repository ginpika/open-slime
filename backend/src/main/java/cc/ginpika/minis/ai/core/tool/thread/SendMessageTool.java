package cc.ginpika.minis.ai.core.tool.thread;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/** send_message：往 {@link OpenThreadTool} 开出的会话里投一条说给人看的消息 */
@Component
public class SendMessageTool implements AiTool {

    public static final String NAME = "send_message";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "往一个会话里投一条消息（正文就是要说给人看的话，支持 markdown，图片直接写成 ![](地址)）。"
                + "同一会话可以连续投多条。";
    }

    @Override
    public Set<ToolSurface> surfaces() {
        return Set.of(ToolSurface.THREAD);
    }

    @Override
    public ObjectNode parameters(ObjectMapper om) {
        ObjectNode params = om.createObjectNode();
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");
        props.putObject("thread_id").put("type", "string")
                .put("description", OpenThreadTool.NAME + " 返回的会话 id");
        props.putObject("content").put("type", "string").put("description", "消息正文（支持 markdown）");
        params.putArray("required").add("thread_id").add("content");
        return params;
    }
}
