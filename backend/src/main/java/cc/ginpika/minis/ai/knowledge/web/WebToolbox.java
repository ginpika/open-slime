package cc.ginpika.minis.ai.knowledge.web;

import cc.ginpika.minis.ai.core.tool.doc.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class WebToolbox {
    private final WebBrowserService browser;
    private final WebSnapshotService snapshots;
    private final ObjectMapper om;
    public WebToolbox(WebBrowserService browser,WebSnapshotService snapshots,ObjectMapper om){this.browser=browser;this.snapshots=snapshots;this.om=om;}
    public static boolean handles(String name){return WebBrowserTool.NAME.equals(name)||SaveWebSnapshotTool.NAME.equals(name);}
    public String run(String name,JsonNode args){
        String owner=WebSnapshotService.agentOwner();
        try {
            if(SaveWebSnapshotTool.NAME.equals(name)){
                List<WebDtos.Claim> claims=args.has("claims")?om.convertValue(args.get("claims"),om.getTypeFactory().constructCollectionType(List.class,WebDtos.Claim.class)):List.of();
                var result=snapshots.save(owner,new WebDtos.SaveRequest(args.path("capture_id").asText(),args.path("title").asText(),
                        args.path("summary").asText(),args.path("rationale").asText(),claims,"agent"));
                return om.writeValueAsString(Map.of("id",result.id(),"doc_id",result.docId(),"title",result.title(),"url",result.url(),
                        "local_url","#/snapshots/"+result.id(),"index_status",result.indexStatus(),"note","正文已进入关键词检索；向量在后台生成。图谱可在文档分析中审核提取。"));
            }
            String action=args.path("action").asText();
            if(action.equals("capture")||action.equals("read_capture")){
                var capture=action.equals("capture")?browser.capture(owner,args.path("browser_id").asText(),
                        new WebDtos.MediaOptions(args.path("images").asBoolean(true),args.path("audio").asBoolean(false),args.path("video").asBoolean(false))):browser.captured(owner,args.path("capture_id").asText());
                String evidence=capture.paragraphs().stream().map(p->"["+p.id()+"] "+p.text()).collect(java.util.stream.Collectors.joining("\n\n"));
                int offset=Math.max(0,Math.min(args.path("offset").asInt(),evidence.length()));
                return om.writeValueAsString(Map.of("capture_id",capture.captureId(),"browser_id",capture.browserId(),"title",capture.title(),"url",capture.url(),
                        "warnings",capture.warnings(),"evidence",evidence.substring(offset,Math.min(offset+20_000,evidence.length())),"offset",offset,
                        "total_chars",evidence.length(),"content_hash",capture.contentHash()));
            }
            var result=browser.command(owner,WebSnapshotService.chatSession(),new WebDtos.Command(args.path("browser_id").asText(),action,
                    args.path("url").asText(),args.path("ref").asText(),args.path("text").asText(),args.path("offset").asInt(),null,null),false);
            if(result==null)return "浏览器已关闭";
            var output=om.createObjectNode();
            output.put("browser_id",result.browserId());output.put("url",result.url());output.put("title",result.title());
            output.put("text",result.text());output.put("total_chars",result.totalChars());output.put("offset",result.offset());
            output.set("elements",om.valueToTree(result.elements()));output.put("blocked",result.blocked());
            return om.writeValueAsString(output);
        }catch(Exception e){return "网页工具失败："+e.getMessage();}
    }
}
