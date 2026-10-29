package cc.ginpika.minis.ai.knowledge.ingest;

import cc.ginpika.minis.ai.core.prompt.PromptManager;
import cc.ginpika.minis.ai.core.tool.ToolManager;
import cc.ginpika.minis.ai.knowledge.dto.AnalyzeDtos.Triple;
import cc.ginpika.minis.ai.llm.OpenAiChatClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnalyzeLoopTest {
    @Test void commitContinuesPastTheFormerEightRoundLimitUntilAnAnswer(){
        var json=new ObjectMapper();var client=mock(OpenAiChatClient.class);var tools=mock(ToolManager.class);
        when(client.configured()).thenReturn(true);when(tools.toolsOf(any())).thenReturn(json.createArrayNode());
        var calls=new AtomicInteger();
        when(client.chatRaw(any(ObjectNode.class),anyString())).thenAnswer(inv->{
            var message=json.createObjectNode().put("role","assistant");int turn=calls.incrementAndGet();
            if(turn<=10) message.putArray("tool_calls").addObject().put("id","call-"+turn).putObject("function").put("name","unknown").put("arguments","{}");
            else message.put("content","completed");
            return new OpenAiChatClient.RawResult(message,"raw");
        });
        var service=new AnalyzeService(client,null,null,null,null,null,json,mock(PromptManager.class),tools);
        var result=service.commit(List.of(new Triple("subject","relates_to","object",null,null,null,null,null,null)));
        assertEquals(10,result.toolCalls().size());assertEquals("completed",result.finish());assertEquals(11,calls.get());
    }
}
