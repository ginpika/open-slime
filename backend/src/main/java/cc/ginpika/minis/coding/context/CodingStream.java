package cc.ginpika.minis.coding.context;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.function.BiConsumer;

/** Persist provider-visible reasoning and text separately, including interrupted streams. */
public final class CodingStream {
    private final int attempt;
    private final BiConsumer<String,Object> events;
    private final StringBuilder text=new StringBuilder(),reasoning=new StringBuilder(),observedReasoning=new StringBuilder();
    private long flushedAt=System.currentTimeMillis();
    private int round;

    public CodingStream(int attempt,BiConsumer<String,Object> events){this.attempt=attempt;this.events=events;}

    public void delta(int round,String thought,String content){
        if(this.round!=round){flush();this.round=round;observedReasoning.setLength(0);}
        if(thought!=null){reasoning.append(thought);observedReasoning.append(thought);}
        if(content!=null){text.append(content);}
        if(reasoning.length()+text.length()>=512 || System.currentTimeMillis()-flushedAt>=150){flush();}
    }

    public void finish(int round,JsonNode message){
        flush();this.round=round;
        String full=message.path("reasoning_content").asText("");
        if(full.isBlank()){full=observedReasoning.toString();}
        if(!full.isBlank()){events.accept("reasoning.finished",Map.of("attempt",attempt,"round",round,"text",full));}
        observedReasoning.setLength(0);
    }

    public void flush(){
        emit("reasoning.delta",reasoning);emit("message.delta",text);flushedAt=System.currentTimeMillis();
    }
    /** A failed request has no committed model result; reset its visible deltas before retry. */
    public void restart(int round){text.setLength(0);reasoning.setLength(0);observedReasoning.setLength(0);this.round=round;flushedAt=System.currentTimeMillis();}
    private void emit(String type,StringBuilder value){
        if(!value.isEmpty()){events.accept(type,Map.of("attempt",attempt,"round",round,"text",value.toString()));value.setLength(0);}
    }
}
