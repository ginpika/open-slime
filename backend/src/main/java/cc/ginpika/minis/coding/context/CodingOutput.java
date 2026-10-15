package cc.ginpika.minis.coding.context;

import cc.ginpika.minis.ai.llm.OpenAiChatClient;
import cc.ginpika.minis.ai.runtime.ModelLoop;
import cc.ginpika.minis.coding.api.CodingDtos.Run;
import cc.ginpika.minis.coding.config.CodingProps;
import cc.ginpika.minis.coding.store.CodingStore;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;

/** Per-execution output recovery. A truncated response never reaches tool dispatch. */
public final class CodingOutput {
    private final CodingProps props;
    private final CodingStore store;
    private final Run run;
    private final java.util.function.BiConsumer<ArrayNode,Integer> checkpoint;
    private int consecutive;
    private final StringBuilder answerParts=new StringBuilder();

    public CodingOutput(CodingProps props,CodingStore store,Run run){this(props,store,run,(messages,round)->store.checkpoint(run.id(),messages,round));}
    public CodingOutput(CodingProps props,CodingStore store,Run run,java.util.function.BiConsumer<ArrayNode,Integer> checkpoint){this.props=props;this.store=store;this.run=run;this.checkpoint=checkpoint;}

    public static void configure(ObjectNode body,CodingProps props,int window,Integer modelLimit){
        int limit=modelLimit!=null && modelLimit>0?modelLimit:props.getMaxResponseTokens();
        // A context window is not an output capability. Unknown provider limits stay unspecified.
        if(limit>0){body.put("max_tokens",Math.min(limit,Math.max(256,window/2)));}
        else{body.remove("max_tokens");}
    }
    public static boolean truncated(OpenAiChatClient.RawResult raw){
        return "length".equals(raw.finishReason()) || "max_tokens".equals(raw.finishReason());
    }
    public boolean continueResponse(int round,ObjectNode body,OpenAiChatClient.RawResult raw){
        if(!truncated(raw)){
            consecutive=0;
            if(!raw.message().path("tool_calls").isEmpty()){answerParts.setLength(0);}
            return false;
        }
        consecutive++;
        ArrayNode messages=(ArrayNode)body.get("messages");
        String partial=raw.message().path("content").asText("");
        if(raw.message().path("tool_calls").isEmpty() && !partial.isBlank()){
            if(!answerParts.isEmpty()){answerParts.append('\n');}
            answerParts.append(partial);
        }
        // Reasoning is protocol history, not a tool. Discard only the incomplete tool calls.
        ObjectNode safe=ModelLoop.assistant(raw.message());safe.remove("tool_calls");
        safe.put("content",partial.isBlank()?"[本轮输出被模型服务截断，未执行本轮工具调用。]":partial);
        messages.add(safe);
        messages.addObject().put("role","user").put("content","""
                [运行时自动续接] 上轮达到单次模型输出限制，不是整个任务的 token 预算。
                本轮所有工具调用均未执行，不要假定文件已修改或命令已运行。
                继续原用户目标。立即缩小步骤：每次只输出少量完整工具调用，每个补丁只修改一个小片段。
                创建长文件先写可用的小段，再 read_file 取得新 hash 后用 apply_patch 补齐；不要一次生成整个项目。
                不要重复长篇分析或复述已展示内容。先核对实际文件状态，再给出完整、较短的工具参数。
                若当前只是分段回答，接着完成尚未输出的部分即可，不要把半截回答当作完成。
                """);
        boolean automatic=consecutive<=props.getMaxOutputContinuations();
        store.event(run.id(),"response.truncated",Map.of("round",round,"attempt",run.attempt(),
                "continuation",consecutive,"automatic",automatic,"outputLimit",body.path("max_tokens").asInt(0),
                "discardedToolCalls",raw.message().path("tool_calls").size()));
        checkpoint.accept(messages,round);
        if(!automatic){throw new CodingContext.ContextOverflow("模型连续 "+consecutive+" 次截断输出，自动续接仍未完成；检查点已保存。请在模型设置调整单次输出上限，或继续时缩小任务。");}
        return true;
    }
    public String answer(String last){return answerParts.isEmpty()?last:answerParts+"\n"+last;}
    public void discardAnswerParts(){answerParts.setLength(0);}
}
