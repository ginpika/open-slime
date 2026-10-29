package cc.ginpika.minis.coding.integration;

import cc.ginpika.minis.ai.core.*;
import cc.ginpika.minis.ai.memory.*;
import cc.ginpika.minis.ai.memory.recall.MemoryItemRecall;
import cc.ginpika.minis.coding.api.CodingDtos.*;
import cc.ginpika.minis.config.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Adapter at the coding boundary; the execution engine remains independent of memory storage. */
@Component
public class CodingMemory {
    private static final Logger log=LoggerFactory.getLogger(CodingMemory.class);
    private final MemorySnapshotBuilder snapshots;
    private final MemoryIngest ingest;
    private final MemoryProps props;
    private final ContextProps context;
    private final ObjectMapper json;
    public CodingMemory(MemorySnapshotBuilder snapshots,MemoryIngest ingest,MemoryProps props,ContextProps context,ObjectMapper json) {
        this.snapshots=snapshots;this.ingest=ingest;this.props=props;this.context=context;this.json=json;
    }
    private MemoryExec scope(Run run,Repository repo,int round) {
        String project=repo.projectId()==null?"coding-repository:"+repo.id():repo.projectId();
        return new MemoryExec(null,BuiltinAgents.CODING,project,run.sessionId()==null?"coding-run:"+run.id():run.sessionId(),run.taskId(),
                MemorySurface.PLAN,false,"coding:"+run.id()+":"+run.attempt()+":"+round,MemoryIdentity.REAL);
    }
    public void refresh(Run run,Repository repo,ArrayNode messages,int round) {
        if(!props.isEnabled() || !context.isInjectMemory() || messages.isEmpty()) return;
        try {
            var exec=scope(run,repo,round);var snapshot=snapshots.build(exec,run.prompt(),exec.turnId());
            var system=(ObjectNode)messages.get(0);String original=system.path("content").asText("");
            int start=original.indexOf(MemoryLiveContext.START),end=original.indexOf(MemoryLiveContext.END);
            if(start>=0 && end>=start) original=original.substring(0,start)+original.substring(end+MemoryLiveContext.END.length());
            StringBuilder body=new StringBuilder(MemoryItemRecall.hitsText(snapshot.items().stream().map(i->new MemoryItemRecall.Hit(i,0)).toList()));
            for(var work:snapshot.work()) body.append('\n').append(MemorySnapshotBuilder.workLine(work,props.getBodyMaxChars()));
            system.put("content",original+"\n"+MemoryLiveContext.START+"\n项目与 Agent 的已授权记忆；历史结果只代表当时版本，修改前重新核对代码与验证证据：\n"+body+"\n"+MemoryLiveContext.END);
            system.set("_minis_memory",json.valueToTree(snapshot));
        } catch(RuntimeException e) { log.warn("编码记忆召回失败，本轮继续：{}",e.getMessage()); }
    }
    public void completed(Run run,Repository repo,String answer) {
        if(!props.isEnabled() || answer==null || answer.isBlank()) return;
        try { ingest.tapExec(scope(run,repo,run.round()),"编码工作结果（待验收，不能视为已应用）",answer); }
        catch(RuntimeException e) {log.warn("编码结论记忆采集失败：{}",e.getMessage());}
    }
}
