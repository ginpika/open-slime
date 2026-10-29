package cc.ginpika.minis.ai.runtime;

import cc.ginpika.minis.ai.llm.OpenAiChatClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ModelLoopProtocolTest {
    private final ObjectMapper json=new ObjectMapper();
    private final OpenAiChatClient client=mock(OpenAiChatClient.class);
    private ObjectNode body(){var body=json.createObjectNode();body.putArray("messages").addObject().put("role","user").put("content","goal");body.putArray("tools");return body;}
    private OpenAiChatClient.RawResult answer(){return new OpenAiChatClient.RawResult(json.createObjectNode().put("role","assistant").put("content","answer").put("reasoning_content","provider reasoning"),"raw","stop");}
    private ModelLoop.Hooks retries(List<Integer> attempts){return new ModelLoop.Hooks(){
        @Override public int maxRequestRetries(){return 3;}
        @Override public long retryDelayMillis(int retry){return 0;}
        @Override public void retrying(int round,int retry,int maximum,long delay,RuntimeException error){attempts.add(retry);}
    };}
    private ModelLoop.Result run(ObjectNode body,ModelLoop.Hooks hooks){return new ModelLoop(client).run(new ModelLoop.Spec(null,"coding",body,false,calls->List.of(),hooks,null));}

    @Test void plainAnswerAndContinuedAnswerPreserveTheExactProviderReasoning(){
        when(client.chatRaw(any(),any(),any(),anyString())).thenReturn(answer());
        var body=body();AtomicInteger turns=new AtomicInteger();
        var result=run(body,new ModelLoop.Hooks(){
            @Override public boolean continueAfterAnswer(int round,com.fasterxml.jackson.databind.JsonNode message,String content){
                if(turns.getAndIncrement()==0){((com.fasterxml.jackson.databind.node.ArrayNode)body.get("messages")).add(message);return true;}
                return false;
            }
        });
        assertEquals("provider reasoning",body.path("messages").get(1).path("reasoning_content").asText());
        assertEquals("provider reasoning",result.message().path("reasoning_content").asText());
    }
    @Test void initialRequestPlusThreeRetriesCanRecoverWithoutConsumingExtraToolRounds(){
        var failure=new OpenAiChatClient.ChatFailure(503,"temporarily unavailable",null);List<Integer> attempts=new ArrayList<>();
        when(client.chatRaw(any(),any(),any(),anyString())).thenThrow(failure,failure,failure).thenReturn(answer());
        var result=run(body(),retries(attempts));assertEquals(ModelLoop.End.ANSWERED,result.end());assertEquals(0,result.round());
        assertEquals(List.of(1,2,3),attempts);verify(client,times(4)).chatRaw(any(),any(),any(),anyString());
    }
    @Test void exhaustedRetriesStopAfterFourRequests(){
        when(client.chatRaw(any(),any(),any(),anyString())).thenThrow(new OpenAiChatClient.ChatFailure(429,"rate limited",null));
        assertThrows(OpenAiChatClient.ChatFailure.class,()->run(body(),retries(new ArrayList<>())));
        verify(client,times(4)).chatRaw(any(),any(),any(),anyString());
    }
    @Test void invalidRequestsAndApplicationFailuresAreNotBlindlyRetried(){
        for(RuntimeException failure:List.of(new OpenAiChatClient.ChatFailure(400,"missing reasoning_content",null),new OpenAiChatClient.ChatFailure(401,"invalid key",null),new IllegalStateException("checkpoint error"))){
            reset(client);when(client.chatRaw(any(),any(),any(),anyString())).thenThrow(failure);
            assertSame(failure,assertThrows(RuntimeException.class,()->run(body(),retries(new ArrayList<>()))));verify(client).chatRaw(any(),any(),any(),anyString());
        }
    }
    @Test void cancellationDuringBackoffStopsBeforeAnotherRequest(){
        var control=new OpenAiChatClient.CallControl();when(client.chatRaw(any(),any(),any(),anyString(),any())).thenThrow(new OpenAiChatClient.ChatFailure(0,"connection reset",null));
        var hooks=new ModelLoop.Hooks(){@Override public int maxRequestRetries(){return 3;}@Override public void retrying(int round,int retry,int maximum,long delay,RuntimeException error){control.cancel();}};
        assertThrows(CancellationException.class,()->new ModelLoop(client).run(new ModelLoop.Spec(null,"coding",body(),false,calls->List.of(),hooks,control)));
        verify(client).chatRaw(any(),any(),any(),anyString(),any());
    }
    @Test void retriesNeverRedispatchToolsFromThePreviousRound(){
        var tool=json.createObjectNode().put("role","assistant").put("reasoning_content","read first");var call=tool.putArray("tool_calls").addObject().put("id","c1").put("type","function");call.putObject("function").put("name","read_file").put("arguments","{}");
        when(client.chatRaw(any(),any(),any(),anyString())).thenReturn(new OpenAiChatClient.RawResult(tool,"raw","tool_calls"))
                .thenThrow(new OpenAiChatClient.ChatFailure(502,"bad gateway",null)).thenReturn(answer());
        AtomicInteger dispatched=new AtomicInteger();var body=body();
        var result=new ModelLoop(client).run(new ModelLoop.Spec(null,"coding",body,false,calls->{dispatched.incrementAndGet();return List.of("file contents");},retries(new ArrayList<>()),null));
        assertEquals(ModelLoop.End.ANSWERED,result.end());assertEquals(1,dispatched.get());assertEquals(3,body.path("messages").size());
        assertEquals("read first",body.path("messages").get(1).path("reasoning_content").asText());
    }
    @Test void anUnboundedToolLoopStillStopsImmediatelyWhenCancelled(){
        var control=new OpenAiChatClient.CallControl();var executed=new AtomicInteger();
        var tool=json.createObjectNode().put("role","assistant");
        tool.putArray("tool_calls").addObject().put("id","read-1").putObject("function").put("name","read_file").put("arguments","{}");
        when(client.chatRaw(any(),any(),any(),anyString(),any())).thenReturn(new OpenAiChatClient.RawResult(tool,"raw"));
        assertThrows(CancellationException.class,()->new ModelLoop(client).run(new ModelLoop.Spec(null,"coding",body(),false,calls->{
            if(executed.incrementAndGet()==256) control.cancel();
            return List.of("result");
        },null,control)));
        assertEquals(256,executed.get());
    }

}
