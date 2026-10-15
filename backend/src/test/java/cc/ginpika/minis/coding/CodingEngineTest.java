package cc.ginpika.minis.coding;

import cc.ginpika.minis.ai.llm.OpenAiChatClient;
import cc.ginpika.minis.ai.runtime.ModelLoop;
import cc.ginpika.minis.coding.api.CodingDtos.*;
import cc.ginpika.minis.coding.config.CodingProps;
import cc.ginpika.minis.coding.context.CodingContext;
import cc.ginpika.minis.coding.engine.CodingTools;
import cc.ginpika.minis.config.ModelTarget;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CodingEngineTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void devIdentityCannotRegisterOrUseCodeFromRemoteConnections(){
        assertTrue(CodingFacade.allowsActorAt("local-dev","127.0.0.1"));
        assertTrue(CodingFacade.allowsActorAt("local-dev","::1"));
        assertFalse(CodingFacade.allowsActorAt("local-dev","192.168.1.20"));
        assertFalse(CodingFacade.allowsActorAt(null,"127.0.0.1"));
        assertTrue(CodingFacade.allowsActorAt("authenticated-user","192.168.1.20"));
    }
    @Test void codingBudgetDoesNotForceAFalseFinalAnswer(){
        var client=mock(OpenAiChatClient.class);
        var answer=json.createObjectNode().put("role","assistant");var call=answer.putArray("tool_calls").addObject().put("id","call-1").put("type","function");call.putObject("function").put("name","read_file").put("arguments","{}");
        when(client.chatRaw(any(),any(),any(),any())).thenReturn(new OpenAiChatClient.RawResult(answer,"raw"));
        ObjectNode body=json.createObjectNode();body.putArray("messages").addObject().put("role","user").put("content","fix");body.putArray("tools").addObject().put("type","function");
        var result=new ModelLoop(client).run(new ModelLoop.Spec(new ModelTarget("url","key","model"),"coding",body,1,false,calls->java.util.List.of("result"),null,null));
        assertEquals(ModelLoop.End.ROUNDS_EXHAUSTED,result.end());verify(client,times(1)).chatRaw(any(),any(),any(),any());assertTrue(body.has("tools"));
    }
    @Test void reasoningAloneIsNotACodingDeliverable(){
        var client=mock(OpenAiChatClient.class);
        var message=json.createObjectNode().put("role","assistant").put("reasoning_content","internal reasoning");
        when(client.chatRaw(any(),any(),any(),any())).thenReturn(new OpenAiChatClient.RawResult(message,"raw"));
        var body=json.createObjectNode();body.putArray("messages").addObject().put("role","user").put("content","fix");
        var result=new ModelLoop(client).run(new ModelLoop.Spec(new ModelTarget("url","key","model"),"coding",body,1,false,calls->java.util.List.of(),null,null));
        assertEquals(ModelLoop.End.EMPTY_CONTENT,result.end());assertNull(result.content());
    }
    @Test void readOnlyModeNeverExposesWriteOrShellTools(){
        var tools=new CodingTools(json,null,null,null);var names=tools.schemas(Mode.PLAN).toString();
        assertTrue(names.contains("read_file"));assertFalse(names.contains("apply_patch"));assertFalse(names.contains("start_process"));
        assertTrue(tools.schemas(Mode.EDIT).toString().contains("apply_patch"));assertFalse(tools.schemas(Mode.EDIT).toString().contains("start_process"));
    }
    @Test void compactionKeepsUserConstraintsAndWholeToolPairs(){
        CodingProps props=new CodingProps();props.setContextChars(20_000);
        var context=new CodingContext(json,null,mock(cc.ginpika.minis.coding.store.CodingStore.class),props);
        ObjectNode body=json.createObjectNode();var messages=body.putArray("messages");messages.addObject().put("role","system").put("content","rules");messages.addObject().put("role","user").put("content","Never change the public API");
        for(int i=0;i<20;i++){var assistant=messages.addObject().put("role","assistant");var call=assistant.putArray("tool_calls").addObject().put("id","c"+i);call.putObject("function").put("name","read_file").put("arguments","{}");messages.addObject().put("role","tool").put("tool_call_id","c"+i).put("content","x".repeat(1800));}
        var run=new Run("id",1,"owner","repo","goal",null,Mode.EDIT,Runner.DOCKER,"RUNNING",1,0,null,null,"NOT_RUN",null,0,0);
        context.compact(run,body,100_000);
        assertTrue(messages.toString().contains("Never change the public API"));
        for(int i=0;i<messages.size();i++){if("tool".equals(messages.get(i).path("role").asText())){assertTrue(i>0);assertTrue(messages.get(i-1).path("tool_calls").isArray());}}
    }
}
