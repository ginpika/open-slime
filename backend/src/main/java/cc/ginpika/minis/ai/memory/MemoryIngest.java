package cc.ginpika.minis.ai.memory;

import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.memory.store.MemoryIngestStore;
import cc.ginpika.minis.config.MemoryProps;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Persistent, source-linked extraction queue. Conversation latency is isolated from model work. */
@Component
public class MemoryIngest {
    private static final Logger log=LoggerFactory.getLogger(MemoryIngest.class);
    private final MemoryProps props;
    private final MemoryExtractor extractor;
    private final MemoryIngestStore jobs;
    private final ExecutorService work=Executors.newVirtualThreadPerTaskExecutor();
    private ScheduledExecutorService scanner;
    public MemoryIngest(MemoryProps props,MemoryExtractor extractor,MemoryIngestStore jobs) {
        this.props=props;this.extractor=extractor;this.jobs=jobs;
    }
    @PostConstruct void start() {
        scanner=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"memory-ingest");t.setDaemon(true);return t;});
        scanner.scheduleWithFixedDelay(this::scan,1000,30_000,TimeUnit.MILLISECONDS);
    }
    @PreDestroy void stop() {
        if(scanner!=null) scanner.shutdownNow();
        work.shutdown();
    }
    public void tap(String session,MemoryExec exec,String user,String assistant) { tap(session,exec,user,assistant,null); }
    public void tap(String session,MemoryExec exec,String user,String assistant,String refs) {
        if(!props.isEnabled() || session==null || user==null || user.isBlank()) return;
        MemoryExec scope=ScopeGuard.resolve(exec);
        if(!ScopeGuard.allowWrite(props,scope,"MemoryIngest.tap")) return;
        boolean explicit=MemoryExtractor.isExplicit(user);
        refs=MemoryTime.anchor(refs,props.getTimeZone());
        String text="【原消息时间与来源】\n"+MemoryTime.material(refs)+"\n【用户】"+user.strip()+(assistant==null || assistant.isBlank()?"":"\n【助手】"+assistant.strip());
        String group=jobs.enqueue(scope,text,refs,explicit,props.getIdleMs());
        if(explicit) work.execute(()->flush(group));
    }
    public void tapExec(String title,String output) { tapExec(null,title,output); }
    public void tapExec(MemoryExec explicit,String title,String output) {
        MemoryExec exec=ScopeGuard.resolve(explicit);
        if(!props.isEnabled() || exec==null || output==null || output.isBlank()) return;
        String text="【"+(title==null?"执行结论":title)+"】\n"+output;
        String refs=exec.taskId()==null?null:MemoryEvidence.ref("task",exec.taskId(),text);
        refs=MemoryTime.anchor(refs,props.getTimeZone());
        String group=jobs.enqueue(exec,text,refs,false,0);
        work.execute(()->flush(group));
    }
    public CompletableFuture<Boolean> distill(MemoryExec exec,String text,String refs) { return extractor.distillAsync(exec,text,refs); }
    public void rewind(String session,Set<Long> ids) {
        jobs.rewind(session,ids.stream().map(id->"chat_message:"+id).collect(java.util.stream.Collectors.toSet()));
    }
    private void scan() {
        if(!props.isEnabled()) return;
        try { for(String group:jobs.dueGroups(props.getBatchTurns())) work.execute(()->flush(group)); }
        catch(RuntimeException e) { log.warn("记忆提炼队列扫描失败，稍后重试：{}",e.getMessage()); }
    }
    private void flush(String group) {
        List<MemoryIngestStore.Job> batch=jobs.claim(group,props.getBatchTurns());
        if(batch.isEmpty()) return;
        try {
            String text=batch.stream().map(job->"【本段来源时间】\n"+MemoryTime.material(job.refs())+"\n"+job.text()).collect(java.util.stream.Collectors.joining("\n\n"));
            String refs=batch.stream().map(MemoryIngestStore.Job::refs).reduce(null,MemoryEvidence::merge);
            for(var job:batch) refs=MemoryEvidence.merge(refs,"[{\"ref\":\"graph_job:"+job.id()+":"+job.token()+"\"}]");
            var future=extractor.extractConversationAsync(batch.getLast().exec(),text,batch.stream().anyMatch(MemoryIngestStore.Job::explicit),refs);
            future.whenComplete((ok,error)->jobs.complete(batch,error==null && Boolean.TRUE.equals(ok),error==null?null:error.getMessage()));
        } catch(RuntimeException e) { jobs.complete(batch,false,e.getMessage()); }
    }
}
