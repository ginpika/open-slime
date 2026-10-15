package cc.ginpika.minis.ai.core.tool.doc;

import cc.ginpika.minis.ai.core.tool.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class SaveWebSnapshotTool implements AiTool {
    public static final String NAME="save_web_snapshot";
    public String name(){return NAME;}
    public Set<ToolSurface> surfaces(){return Set.of(ToolSurface.CHAT);}
    public ToolDomain domain(){return ToolDomain.DOC;}
    public boolean writes(){return true;}
    public String description(){return "将 web_browser.capture 获得的正文证据保存为离线 HTML 快照并发布到本地知识库。"
            + "仅在用户要求保存，或已授权在研究中筛选和积累知识时调用。rationale 必填：说明价值、来源质量、时效与局限。"
            + "summary 为生成概述，claims 为忠于原文的论点，kind 为 fact/opinion/inference，每条 evidence 引用 capture 中存在的 p1 等段落。"
            + "不可只凭搜索摘要保存，不可把观点当事实。正文和生成论点分别保存，相同 URL 与正文自动去重，内容变化保留新版本。";}
    public String validate(JsonNode args){
        if(args.path("capture_id").asText("").isBlank())return "缺少 capture_id：先提取正文证据";
        if(args.path("rationale").asText("").isBlank())return "缺少 rationale：请说明来源质量与保存理由";
        return null;
    }
    public ObjectNode parameters(ObjectMapper om){
        ObjectNode p=om.createObjectNode();p.put("type","object");var f=p.putObject("properties");
        for(String key:new String[]{"capture_id","title","summary","rationale"})f.putObject(key).put("type","string");
        var items=f.putObject("claims").put("type","array").putObject("items");items.put("type","object");var fields=items.putObject("properties");
        fields.putObject("text").put("type","string");fields.putObject("kind").put("type","string").putArray("enum").add("fact").add("opinion").add("inference");
        AiTool.putStringArraySchema(fields,"evidence","支持论点的正文段落编号");items.putArray("required").add("text").add("kind").add("evidence");
        p.putArray("required").add("capture_id").add("rationale");return p;
    }
}
