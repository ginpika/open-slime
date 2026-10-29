package cc.ginpika.minis.coding;

import cc.ginpika.minis.ai.llm.OpenAiChatClient;
import cc.ginpika.minis.ai.runtime.ModelLoop;
import cc.ginpika.minis.coding.api.CodingDtos.*;
import cc.ginpika.minis.coding.config.CodingProps;
import cc.ginpika.minis.coding.context.CodingContext;
import cc.ginpika.minis.coding.context.CodingOutput;
import cc.ginpika.minis.coding.store.CodingStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CodingOutputTest {
    private final ObjectMapper json=new ObjectMapper();
    private final CodingProps props=new CodingProps();
    private final CodingStore store=mock(CodingStore.class);
    private final Run run=new Run("r1",1,"owner","repo","goal",null,Mode.EDIT,Runner.WORKSPACE,"RUNNING",1,0,null,null,"NOT_RUN",null,0,0);
    private ObjectNode body(){var body=json.createObjectNode();body.putArray("messages").addObject().put("role","user").put("content","goal");body.putArray("tools");return body;}
    private OpenAiChatClient.RawResult text(String content,String finish){return new OpenAiChatClient.RawResult(json.createObjectNode().put("role","assistant").put("content",content),"raw",finish);}

    @Test void contextWindowDoesNotCreateAnArbitraryOutputCapability(){
        for(int window:List.of(16000,32768,262144)){var request=body();CodingOutput.configure(request,props,window,null);assertFalse(request.has("max_tokens"));}
    }
    @Test void configuredModelLimitWinsAndInputStillHasRoom(){
        props.setMaxResponseTokens(12000);var request=body();
        CodingOutput.configure(request,props,262144,32768);assertEquals(32768,request.path("max_tokens").asInt());
        CodingOutput.configure(request,props,262144,null);assertEquals(12000,request.path("max_tokens").asInt());
        CodingOutput.configure(request,props,8192,32768);assertTrue(request.path("max_tokens").asInt()<8192);
    }
    @Test void reasoningOnlyTruncationIsContinuedWithoutAnEmptyAnswerOrFakeTools(){
        var recovery=new CodingOutput(props,store,run);var request=body();
        var message=json.createObjectNode().put("role","assistant").put("reasoning_content","unfinished reasoning");
        assertTrue(recovery.continueResponse(0,request,new OpenAiChatClient.RawResult(message,"raw","length")));
        assertEquals("unfinished reasoning",request.path("messages").get(1).path("reasoning_content").asText());
        assertTrue(request.path("messages").toString().contains("不要假定文件已修改"));
        verify(store).checkpoint(eq("r1"),any(),eq(0));
    }
    @Test void partialPlainAnswerIsPreservedAndJoinedWithItsContinuation(){
        var recovery=new CodingOutput(props,store,run);var request=body();
        assertTrue(recovery.continueResponse(0,request,text("first section","length")));
        assertFalse(recovery.continueResponse(1,request,text("second section","stop")));
        assertEquals("first section\nsecond section",recovery.answer("second section"));
    }
    @Test void repeatedTruncationsStopAtABoundedLimitAfterSavingTheCheckpoint(){
        props.setMaxOutputContinuations(2);var recovery=new CodingOutput(props,store,run);var request=body();
        assertTrue(recovery.continueResponse(0,request,text("part 1","length")));
        assertTrue(recovery.continueResponse(1,request,text("part 2","length")));
        assertThrows(CodingContext.ContextOverflow.class,()->recovery.continueResponse(2,request,text("part 3","length")));
        verify(store,times(3)).checkpoint(eq("r1"),any(),anyInt());
    }
    @Test void successfulToolProgressResetsTheConsecutiveTruncationCounter(){
        props.setMaxOutputContinuations(1);var recovery=new CodingOutput(props,store,run);var request=body();
        assertTrue(recovery.continueResponse(0,request,text("first","length")));
        var call=json.createObjectNode();call.putArray("tool_calls").addObject().put("id","valid");
        assertFalse(recovery.continueResponse(1,request,new OpenAiChatClient.RawResult(call,"raw","tool_calls")));
        assertTrue(recovery.continueResponse(2,request,text("next","length")));
        assertEquals("next\nend",recovery.answer("end"));
    }
    @Test void legacyRawEnvelopeStillExposesFinishReason(){
        assertEquals("length",new OpenAiChatClient.RawResult(json.createObjectNode(),"{\"choices\":[{\"finish_reason\":\"length\"}]}" ).finishReason());
        assertNull(new OpenAiChatClient.RawResult(json.createObjectNode(),"non-json").finishReason());
    }
    @Test void recoveryContinuesUntilAnAnswerWithoutDispatchingIncompleteCalls(){
        OpenAiChatClient client=mock(OpenAiChatClient.class);var recovery=new CodingOutput(props,store,run);var request=body();
        var message=json.createObjectNode();message.putArray("tool_calls").addObject().put("id","broken");
        when(client.chatRaw(any(),any(),any(),anyString())).thenReturn(new OpenAiChatClient.RawResult(message,"raw","length"),text("finished","stop"));
        List<JsonNode> executed=new ArrayList<>();
        var result=new ModelLoop(client).run(new ModelLoop.Spec(null,"coding",request,false,calls->{executed.addAll(calls);return List.of();},new ModelLoop.Hooks(){
            @Override public boolean continueAfterResponse(int round,ObjectNode body,OpenAiChatClient.RawResult raw){return recovery.continueResponse(round,body,raw);}
        },null));
        assertEquals(ModelLoop.End.ANSWERED,result.end());assertEquals("finished",result.content());assertTrue(executed.isEmpty());verify(client,times(2)).chatRaw(any(),any(),any(),anyString());
    }
}
