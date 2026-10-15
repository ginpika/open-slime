package cc.ginpika.minis.coding.engine;

import cc.ginpika.minis.coding.api.CodingDtos.*;
import cc.ginpika.minis.coding.repository.CodingWorkspace;
import cc.ginpika.minis.coding.runner.CodingProcesses;
import cc.ginpika.minis.coding.store.CodingStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public class CodingTools {
    private final ObjectMapper json;
    private final CodingWorkspace files;
    private final CodingProcesses processes;
    private final CodingStore store;
    public CodingTools(ObjectMapper json,CodingWorkspace files,CodingProcesses processes,CodingStore store){ this.json=json; this.files=files; this.processes=processes; this.store=store; }
    public ArrayNode schemas(Mode mode) {
        ArrayNode tools=json.createArrayNode();
        schema(tools,"list_files","列出代码仓库内目录，最多四层、1000 项。排除依赖、构建产物与敏感文件。",Map.of("directory","string"),List.of());
        schema(tools,"read_file","分段读取 UTF-8 文件，返回内容 SHA-256；每次最多 500 行。补丁必须使用最近读取的 hash。",Map.of("path","string","startLine","integer","lineCount","integer"),List.of("path"));
        schema(tools,"search_text","按字面文本搜索代码，返回文件与行号。glob 是文件模式，最多 100 条。",Map.of("query","string","glob","string"),List.of("query"));
        schema(tools,"git_status","查看当前任务副本相对基准的修改，不访问用户原仓库。",Map.of(),List.of());
        schema(tools,"git_diff","读取任务副本的代码 Diff，含新增文件；超长 Diff 会标记截断。",Map.of(),List.of());
        if(mode!=Mode.PLAN) {
            ObjectNode params=json.createObjectNode().put("type","object");
            ObjectNode patches=params.putObject("properties").putObject("patches").put("type","array"); patches.put("minItems",1).put("maxItems",16);
            ObjectNode item=patches.putObject("items").put("type","object"), fields=item.putObject("properties");
            for(String key:List.of("path","expectedHash","oldText","newText")){ fields.putObject(key).put("type","string"); }
            fields.putObject("delete").put("type","boolean"); item.putArray("required").add("path").add("expectedHash"); params.putArray("required").add("patches");
            add(tools,"apply_patch","原子修改最多 16 个 UTF-8 文件。expectedHash 使用 read_file 的 hash；创建新文件用 MISSING 和空 oldText。已有文件 oldText 必须唯一匹配，newText 替换该片段。删除需 delete=true。冲突后重新读取，不得猜 hash。",params);
        }
        if(mode==Mode.EXECUTE) {
            schema(tools,"start_process","在已授权任务执行环境中启动命令，返回 processId。默认 Docker 无网络、无宿主凭证。使用 read_process 查看进展，避免反复启动同一命令。",Map.of("command","string","timeoutSeconds","integer"),List.of("command"));
            schema(tools,"read_process","读取一个本任务进程的累计或增量输出，offset 默认 0；running=true 时应继续检查而非宣称通过。",Map.of("processId","string","offset","integer"),List.of("processId"));
            schema(tools,"stop_process","停止任务进程。Docker 模式停止整个任务沙箱中的全部进程，代码副本保留。",Map.of("processId","string"),List.of("processId"));
            schema(tools,"run_validation","运行用户为仓库配置的验证命令，等待退出并将证据绑定当前代码版本。若代码在验证期间变化或无验证入口，如实返回未通过/阻塞。",Map.of(),List.of());
        }
        return tools;
    }
    private void schema(ArrayNode tools,String name,String description,Map<String,String> fields,List<String> required) {
        ObjectNode params=json.createObjectNode().put("type","object"); ObjectNode properties=params.putObject("properties");
        new TreeMap<>(fields).forEach((key,type)->properties.putObject(key).put("type",type));
        ArrayNode names=params.putArray("required"); required.forEach(names::add); add(tools,name,description,params);
    }
    private void add(ArrayNode tools,String name,String description,ObjectNode params) {
        tools.addObject().put("type","function").putObject("function").put("name",name).put("description",description).set("parameters",params);
    }
    public List<String> execute(Run run,Repository repository,List<JsonNode> calls,AtomicBoolean stopped) {
        synchronized(files.mutex(run.id())){return executeLocked(run,repository,calls,stopped);}
    }
    private List<String> executeLocked(Run run,Repository repository,List<JsonNode> calls,AtomicBoolean stopped) {
        Set<String> granted=new HashSet<>(); schemas(run.mode()).forEach(t->granted.add(t.path("function").path("name").asText()));
        var results=new ArrayList<String>();
        for(JsonNode call:calls) {
            if(stopped.get()){ throw new CancellationException("任务已停止"); }
            String name=call.path("function").path("name").asText(), args=call.path("function").path("arguments").asText("{}");
            if(!granted.contains(name)){ results.add(encode(Map.of("status","DENIED","error","本任务未授权工具："+name))); continue; }
            long operation=store.beginOperation(run,call.path("id").asText(),name,args);
            store.event(run.id(),"tool.started",Map.of("operationId",operation,"tool",name,"args",args,"attempt",run.attempt()));
            try {
                JsonNode input=json.readTree(args); if(!input.isObject()){ throw new IllegalArgumentException("参数必须是 JSON 对象"); }
                Object output=switch(name) {
                    case "list_files" -> files.list(run.id(),input.path("directory").asText("."));
                    case "read_file" -> files.read(run.id(),input.path("path").asText(),input.path("startLine").asInt(1),input.path("lineCount").asInt(200));
                    case "search_text" -> files.search(run.id(),input.path("query").asText(),input.path("glob").asText("**"));
                    case "git_status" -> files.diff(run.id()).changes();
                    case "git_diff" -> files.diff(run.id());
                    case "apply_patch" -> { if(processes.running(run.id())){throw new IllegalArgumentException("还有进程运行，请等它完成或停止后再修改文件");}files.patch(run.id(),input.path("patches")); store.validation(run.id(),"NOT_RUN"); yield Map.of("status","APPLIED","changes",files.diff(run.id()).changes()); }
                    case "start_process" -> { store.validation(run.id(),"NOT_RUN"); yield processes.start(run,input.path("command").asText(),input.path("timeoutSeconds").asInt(120)); }
                    case "read_process" -> processes.read(run.id(),input.path("processId").asText(),input.path("offset").asInt(0));
                    case "stop_process" -> processes.stop(run,input.path("processId").asText());
                    case "run_validation" -> validate(run,repository,stopped);
                    default -> throw new IllegalArgumentException("工具不存在");
                };
                String result=encode(output); if(result.length()>170_000){ result=encode(Map.of("status","OUTPUT_TOO_LARGE","message","请分段读取，结果未完整发送")); }
                store.endOperation(operation,"DONE",result); store.event(run.id(),"tool.finished",Map.of("operationId",operation,"tool",name,"args",args,"attempt",run.attempt(),"status","DONE","result",result)); results.add(result);
            } catch(CancellationException error){ store.endOperation(operation,"UNKNOWN",encode(Map.of("error","取消期间工具副作用需核验"))); throw error; }
            catch(Exception error){ String result=encode(Map.of("status","FAILED","error",String.valueOf(error.getMessage()))); store.endOperation(operation,"FAILED",result); store.event(run.id(),"tool.finished",Map.of("operationId",operation,"tool",name,"args",args,"attempt",run.attempt(),"status","FAILED","result",result)); results.add(result); }
        }
        return results;
    }
    public Map<String,Object> validate(Run run,Repository repository,AtomicBoolean stopped) {
        if(run.mode()!=Mode.EXECUTE || repository.validationCommand()==null || repository.validationCommand().isBlank()) {
            store.validation(run.id(),"BLOCKED"); return Map.of("status","BLOCKED","message","未授权执行或仓库未配置验证命令");
        }
        String current=files.treeHash(run.id());
        Run state=store.run(run.id(),run.ownerId());
        if(!processes.running(run.id()) && Objects.equals(current,store.validationHash(run.id())) && Set.of("PASSED","FAILED").contains(state.validationStatus())){
            return Map.of("status",state.validationStatus(),"treeHash",current,"cached",true,"message","同一代码版本已验证，本次未重复运行命令；修改或新的进程执行会使验证证据失效");
        }
        store.validation(run.id(),"NOT_RUN");
        String tree=files.treeHash(run.id());
        Map<String,Object> output;
        try{
            processes.stopAll(run);
            Map<String,Object> started=processes.start(run,repository.validationCommand(),600);
            output=processes.await(run,started.get("processId").toString(),stopped);
            processes.stopAll(run);
        }catch(CancellationException error){throw error;}
        catch(RuntimeException error){
            store.validation(run.id(),"BLOCKED");
            Map<String,Object> report=Map.of("status","BLOCKED","treeHash",tree,"command",repository.validationCommand(),"error",String.valueOf(error.getMessage()));
            store.event(run.id(),"validation.finished",report);return report;
        }
        boolean stable=tree.equals(files.treeHash(run.id()));
        String status=stable && Objects.equals(output.get("exitCode"),0)?"PASSED":stable?"FAILED":"STALE";
        store.validation(run.id(),status,tree);
        Map<String,Object> report=Map.of("status",status,"treeHash",tree,"command",repository.validationCommand(),"process",output);
        store.event(run.id(),"validation.finished",report); return report;
    }
    private String encode(Object value){ try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException(e);} }
}
