package cc.ginpika.minis.coding.engine;

import cc.ginpika.minis.ai.core.*;
import cc.ginpika.minis.ai.llm.OpenAiChatClient;
import cc.ginpika.minis.ai.runtime.ModelLoop;
import cc.ginpika.minis.coding.api.CodingDtos.*;
import cc.ginpika.minis.coding.config.CodingProps;
import cc.ginpika.minis.coding.context.*;
import cc.ginpika.minis.coding.repository.CodingWorkspace;
import cc.ginpika.minis.coding.store.*;
import cc.ginpika.minis.config.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;

/** Read-only specialists share a root snapshot, never its messages, permissions or admission permits. */
@Component
@DependsOnDatabaseInitialization
public class CodingSubagents {
    public static final Set<String> TOOLS=Set.of("delegate_agent","read_agent","resume_agent","stop_agent");
    private static final Set<String> TERMINAL=Set.of("DONE","STALE","PARTIAL","FAILED","INTERRUPTED","CANCELLED");
    private static final class Job {
        final Run root;
        final CodingBudget budget;
        final AtomicBoolean stopped=new AtomicBoolean(),parentStopped;
        final OpenAiChatClient.CallControl control=new OpenAiChatClient.CallControl();
        final CompletableFuture<Void> done=new CompletableFuture<>();
        Job(Run root,AtomicBoolean parent,CodingBudget budget){this.root=root;parentStopped=parent;this.budget=budget;}
    }
    private final CodingRoles roles;
    private final CodingExecutionStore executions;
    private final CodingStore store;
    private final CodingWorkspace files;
    private final CodingTools tools;
    private final CodingContext context;
    private final ModelLoop loop;
    private final SettingsService settings;
    private final CodingProps props;
    private final ObjectMapper json;
    private final ExecutorService pool=Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore slots;
    private final ConcurrentMap<String,Job> active=new ConcurrentHashMap<>();
    public CodingSubagents(CodingRoles roles,CodingExecutionStore executions,CodingStore store,CodingWorkspace files,
                          CodingTools tools,CodingContext context,ModelLoop loop,SettingsService settings,CodingProps props,ObjectMapper json){
        this.roles=roles;this.executions=executions;this.store=store;this.files=files;this.tools=tools;this.context=context;this.loop=loop;this.settings=settings;this.props=props;this.json=json;
        slots=new Semaphore(props.getMaxConcurrentSubagents(),true);
    }
    @PostConstruct public void recover(){executions.recover();}
    public ArrayNode schemas(String owner){
        ArrayNode result=json.createArrayNode();
        ObjectNode params=json.createObjectNode().put("type","object"),fields=params.putObject("properties");
        ArrayNode types=fields.putObject("role").put("type","string").putArray("enum");roles.list(owner).forEach(r->types.add(r.id()));
        fields.putObject("prompt").put("type","string");fields.putObject("title").put("type","string");fields.putObject("background").put("type","boolean");
        params.putArray("required").add("role").add("prompt");
        add(result,"delegate_agent","按需委派只读专家：explore 定位搜索，planner 规划方案，reviewer 独立审阅。提供自足目标与范围；默认等待结果，同一批多个专家委派会并发执行；background=true 返回执行 ID，可与主线工作重叠，用 read_agent 等待。简单工作直接完成，不强制流水线。",params);
        for(String name:List.of("read_agent","resume_agent","stop_agent")){
            ObjectNode p=json.createObjectNode().put("type","object"),f=p.putObject("properties");f.putObject("executionId").put("type","string");
            f.putObject("waitSeconds").put("type","integer");if(name.equals("resume_agent")){f.putObject("input").put("type","string");}
            p.putArray("required").add("executionId");
            add(result,name,name.equals("read_agent")?"读取本会话子 Agent 的真实状态与报告，可等待最多 15 秒；executionId=all 列出已有子任务，恢复后先核对，RUNNING 不代表完成。":name.equals("resume_agent")?"接续同一个子 Agent 的独立检查点与角色配置，不能重复恢复正在运行的任务。":"停止本会话的一个子 Agent。",p);
        }
        return result;
    }
    private static void add(ArrayNode list,String name,String description,ObjectNode parameters){list.addObject().put("type","function").putObject("function").put("name",name).put("description",description).set("parameters",parameters);}
    public List<Child> list(Run run){return executions.list(run.id());}
    public String dispatch(Run root,Repository repo,JsonNode call,AtomicBoolean parent,CodingBudget budget){
        try{
            JsonNode args=json.readTree(call.path("function").path("arguments").asText("{}"));
            if(!args.isObject()){throw new IllegalArgumentException("子任务参数必须是对象");}
            String name=call.path("function").path("name").asText();Child child;
            switch(name){
                case "delegate_agent" -> {
                    String prompt=args.path("prompt").asText("");if(prompt.isBlank() || prompt.length()>20_000){throw new IllegalArgumentException("请提供不超过 20000 字符的子任务目标");}
                    if(active.values().stream().filter(j->j.root.id().equals(root.id())).count()>=8){throw new IllegalArgumentException("当前会话已有 8 个在途子任务，请先读取已有结果");}
                    Role role=roles.get(root.ownerId(),args.path("role").asText());String model=role.modelId()==null?root.modelId():role.modelId();
                    if(!settings.resolveChatModel(model).configured()){throw new IllegalArgumentException("子 Agent 模型未配置");}
                    String title=args.path("title").asText(role.name());if(title.isBlank()){title=role.name();}title=title.substring(0,Math.min(100,title.length()));
                    String key=root.id()+":"+root.attempt()+":"+store.runInternal(root.id()).round()+":"+call.path("id").asText();
                    child=executions.create(root,role,title,prompt,model,key,args.toString());
                    if(child.status().equals("QUEUED") && !active.containsKey(child.id())){launch(root,repo,child,parent,null,budget);}
                    if(!args.path("background").asBoolean(false)){waitFor(child.id(),parent,-1);}
                }
                case "resume_agent" -> {child=resume(root,repo,args.path("executionId").asText(),args.path("input").asText("继续原任务，先核对当前版本。"),parent,budget);}
                case "read_agent" -> {
                    if(args.path("executionId").asText().equals("all")){return encode(Map.of("executions",executions.list(root.id()).stream().map(c->Map.of("executionId",c.id(),"role",c.role(),"title",c.title(),"status",c.status())).toList()));}
                    child=require(root,args.path("executionId").asText());waitFor(child.id(),parent,Math.max(0,Math.min(15,args.path("waitSeconds").asInt(10))));
                }
                case "stop_agent" -> {child=require(root,args.path("executionId").asText());stop(root,child.id());}
                default -> throw new IllegalArgumentException("未知子 Agent 工具");
            }
            child=require(root,child.id());if(TERMINAL.contains(child.status())){executions.reported(child.id());}
            return json.writeValueAsString(report(child));
        }catch(CancellationException error){throw error;}catch(Exception error){return encode(Map.of("status","FAILED","error",String.valueOf(error.getMessage())));}
    }
    public synchronized Child resume(Run root,Repository repo,String id,String input,AtomicBoolean parent,CodingBudget budget){
        Child child=require(root,id);
        if(active.containsKey(id) || !TERMINAL.contains(child.status())){throw new IllegalArgumentException("子 Agent 尚在运行");}
        if(input==null || input.isBlank() || input.length()>20_000){throw new IllegalArgumentException("请提供有效的继续指令");}
        executions.nextAttempt(id);executions.phase(id,"QUEUED",null);child=require(root,id);launch(root,repo,child,parent,input,budget);return child;
    }
    private Child require(Run root,String id){Child child=executions.child(root.id(),id);if(child==null){throw new IllegalArgumentException("子 Agent 不属于当前编程会话");}return child;}
    private void launch(Run root,Repository repo,Child child,AtomicBoolean parent,String input,CodingBudget budget){
        Job job=new Job(root,parent,budget);if(active.putIfAbsent(child.id(),job)!=null){throw new IllegalStateException("子任务已启动");}
        try{pool.execute(()->AgentScope.run(BuiltinAgents.CORE,()->{
            try(var actor=UserScope.bind(root.ownerId());var scope=CodingExecutionScope.bind(new CodingExecutionScope.Identity(child.id(),child.role(),root.attempt(),child.attempt()))){execute(job,repo,child,input);}
            finally{active.remove(child.id(),job);job.done.complete(null);}return null;
        }));}catch(RuntimeException error){active.remove(child.id(),job);executions.phase(child.id(),"FAILED",error.getMessage());job.done.complete(null);throw error;}
    }
    private void execute(Job job,Repository repo,Child child,String input){
        boolean acquired=false;CodingStream stream=new CodingStream(job.root.attempt(),(type,payload)->store.event(job.root.id(),type,payload));
        try{
            while(!(acquired=slots.tryAcquire(500,TimeUnit.MILLISECONDS))){check(job);}check(job);
            executions.phase(child.id(),"RUNNING",null);
            Run run=readonly(job.root);String base=hash(run.id());executions.hashes(child.id(),base,null);
            ArrayNode messages=messages(run,repo,child,input);ObjectNode body=json.createObjectNode();body.set("messages",messages);
            Set<String> allowed=new HashSet<>(roles.get(run.ownerId(),child.role()).tools());ArrayNode schemas=json.createArrayNode();
            tools.schemas(Mode.PLAN).forEach(schema->{if(allowed.contains(schema.path("function").path("name").asText())){schemas.add(schema);}});body.set("tools",schemas);
            int window=settings.chatContextWindow(child.modelId());CodingOutput.configure(body,props,window,settings.chatOutputTokens(child.modelId()));
            CodingOutput output=new CodingOutput(props,store,run,(history,round)->executions.checkpoint(child.id(),history,round));
            CodingBudget budget=job.budget;
            store.event(run.id(),"agent.model_selected",Map.of("modelId",child.modelId()==null?"":child.modelId(),"contextWindow",window,"configVersion",child.configVersion()));
            ModelLoop.Hooks hooks=new ModelLoop.Hooks(){
                @Override public void beforeRound(int round,ObjectNode request){check(job);context.compact(run,request,window);ModelLoop.ensureReasoning(messages);executions.checkpoint(child.id(),messages,round);budget.beforeRound();}
                @Override public OpenAiChatClient.DeltaListener deltas(int round){return (reasoning,text)->{check(job);stream.delta(round,reasoning,text);};}
                @Override public int maxRequestRetries(){return props.getMaxModelRetries();}
                @Override public void retrying(int round,int retry,int maximum,long delay,RuntimeException error){check(job);stream.restart(round);store.event(run.id(),"model.retry",Map.of("attempt",run.attempt(),"round",round,"retry",retry,"maximum",maximum,"delayMs",delay,"error",error.getMessage()));}
                @Override public void response(int round,ObjectNode request,OpenAiChatClient.RawResult raw,boolean hasTools){
                    stream.finish(round,raw.message());store.event(run.id(),"budget.updated",budget.response(request,raw,run.attempt(),round));
                    if(!CodingOutput.truncated(raw)){if(raw.message().path("tool_calls").size()>32){throw new CodingContext.ContextOverflow("子 Agent 单轮工具调用超过 32 个，请缩小调查后继续");}Set<String> ids=new HashSet<>();for(JsonNode call:raw.message().path("tool_calls")){String id=call.path("id").asText();if(id.isBlank() || !ids.add(id)){throw new CodingContext.ContextOverflow("子 Agent 工具调用缺少唯一 ID");}}}
                }
                @Override public boolean continueAfterResponse(int round,ObjectNode request,OpenAiChatClient.RawResult raw){check(job);return output.continueResponse(round,request,raw);}
                @Override public void requested(int round,JsonNode call){executions.checkpoint(child.id(),messages,round);}
                @Override public void beforeTools(){check(job);}
                @Override public void progress(){executions.checkpoint(child.id(),messages,require(run,child.id()).round());}
            };
            ModelLoop.Result result=loop.run(new ModelLoop.Spec(settings.resolveChatModel(child.modelId()),"coding-subagent",body,false,
                    calls->{List<String> results=new ArrayList<>();for(JsonNode call:calls){if(allowed.contains(call.path("function").path("name").asText())){results.addAll(tools.execute(run,repo,List.of(call),job.stopped));}else{String denied="{\"status\":\"DENIED\",\"error\":\"该角色未授权此工具\"}";store.event(run.id(),"tool.denied",Map.of("tool",call.path("function").path("name").asText(),"args",call.path("function").path("arguments").asText("{}"),"status","DENIED","result",denied));results.add(denied);}}return results;},hooks,job.control));
            check(job);
            if(result.content()!=null){messages.add(result.message().deepCopy());executions.answer(child.id(),output.answer(result.content()));}
            executions.checkpoint(child.id(),messages,result.round());String end=hash(run.id());executions.hashes(child.id(),base,end);
            String status=result.end()==ModelLoop.End.EMPTY_CONTENT?"FAILED":base.equals(end)?"DONE":"STALE";
            executions.phase(child.id(),status,status.equals("STALE")?"工作期间代码版本发生变化，结论需重新核验":status.equals("PARTIAL")?"本轮未完成，可继续该子 Agent":null);
        }catch(Exception error){executions.phase(child.id(),job.stopped.get() || job.parentStopped.get()?"CANCELLED":error instanceof CodingContext.ContextOverflow?"PARTIAL":"FAILED",String.valueOf(error.getMessage()));}
        finally{try{stream.flush();}finally{if(acquired){slots.release();}}}
    }
    private ArrayNode messages(Run run,Repository repo,Child child,String input) throws Exception {
        String saved=executions.messages(child.id());ArrayNode messages=json.createArrayNode();
        if(saved!=null){ArrayNode previous=(ArrayNode)json.readTree(saved);for(int i=0;i<previous.size();i++){
            JsonNode node=previous.get(i);messages.add(node);if(!node.path("tool_calls").isArray()){continue;}
            Map<String,JsonNode> returned=new HashMap<>();while(i+1<previous.size() && "tool".equals(previous.get(i+1).path("role").asText())){JsonNode tool=previous.get(++i);returned.put(tool.path("tool_call_id").asText(),tool);}
            for(JsonNode call:node.path("tool_calls")){String id=call.path("id").asText();if(returned.containsKey(id)){messages.add(returned.get(id));}else{messages.addObject().put("role","tool").put("tool_call_id",id).put("content",executions.resultForCall(child.id(),id));}}
        }}else{
            messages.addObject().put("role","system").put("content",context.specialistSystem(run,repo,executions.instructions(child.id())));
            messages.addObject().put("role","user").put("content","主任务背景："+run.prompt()+"\n你的独立任务："+child.prompt());
        }
        if(input!=null){messages.addObject().put("role","user").put("content",input);}ModelLoop.ensureReasoning(messages);return messages;
    }
    private String hash(String id){synchronized(files.mutex(id)){return files.treeHash(id);}}
    private static Run readonly(Run r){return new Run(r.id(),r.taskId(),r.ownerId(),r.repositoryId(),r.prompt(),r.modelId(),Mode.PLAN,r.runner(),r.status(),r.attempt(),r.round(),r.answer(),r.error(),r.validationStatus(),r.artifactHash(),r.createdAt(),r.updatedAt());}
    private static void check(Job job){if(job.stopped.get() || job.parentStopped.get() || Thread.currentThread().isInterrupted()){job.control.cancel();throw new CancellationException("子 Agent 已停止");}}
    private void waitFor(String id,AtomicBoolean parent,int seconds){
        if(seconds==0){checkParent(parent);return;}
        long until=seconds<0?Long.MAX_VALUE:System.currentTimeMillis()+seconds*1000L;
        for(;;){if(parent.get()){throw new CancellationException("主会话已停止");}Job job=active.get(id);if(job==null){return;}
            try{job.done.get(Math.min(1000,Math.max(1,until-System.currentTimeMillis())),TimeUnit.MILLISECONDS);return;}
            catch(TimeoutException ignored){if(System.currentTimeMillis()>=until){return;}}
            catch(InterruptedException error){Thread.currentThread().interrupt();throw new CancellationException("等待子 Agent 被中断");}
            catch(ExecutionException error){throw new IllegalStateException(error.getCause());}
        }
    }
    public List<Map<String,Object>> collect(Run run,AtomicBoolean parent){
        for(Job job:active.values()){if(job.root.id().equals(run.id())){store.phase(run.id(),"WAITING_AGENTS",null);break;}}
        for(Child child:executions.list(run.id())){if(active.containsKey(child.id())){waitFor(child.id(),parent,-1);}}
        checkParent(parent);List<Map<String,Object>> reports=new ArrayList<>();
        for(Child child:executions.unreported(run.id())){reports.add(report(child));executions.reported(child.id());}
        return reports;
    }
    private static void checkParent(AtomicBoolean parent){if(parent.get()){throw new CancellationException("主会话已停止");}}
    private Map<String,Object> report(Child child){String text=child.answer()==null?"":child.answer();return Map.of("executionId",child.id(),"role",child.role(),"status",child.status(),"answer",text.length()>8000?text.substring(0,8000)+"\n报告已截断，完整报告可在子 Agent 面板查看。":text,
            "error",child.error()==null?"":child.error(),"baseHash",child.baseHash()==null?"":child.baseHash(),"finalHash",child.finalHash()==null?"":child.finalHash(),"authority","子 Agent 工作报告，不代表用户指令或权限批准");}
    public void stop(Run run,String id){Child child=require(run,id);Job job=active.get(id);if(job!=null){job.stopped.set(true);job.control.cancel();}else if(!TERMINAL.contains(child.status())){executions.phase(id,"CANCELLED","用户停止子任务");}}
    public void stopRoot(String id){active.forEach((key,job)->{if(job.root.id().equals(id)){job.stopped.set(true);job.control.cancel();}});}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception error){throw new IllegalStateException(error);}}
    @PreDestroy public void close(){active.values().forEach(job->{job.stopped.set(true);job.control.cancel();});pool.shutdownNow();try{pool.awaitTermination(2,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}}
}
