package cc.ginpika.minis.coding.runner;

import cc.ginpika.minis.ai.terminal.TerminalService;
import cc.ginpika.minis.ai.terminal.store.TerminalStore;
import cc.ginpika.minis.coding.api.CodingDtos.Run;
import cc.ginpika.minis.coding.config.CodingProps;
import cc.ginpika.minis.coding.repository.CodingWorkspace;
import cc.ginpika.minis.coding.store.CodingStore;
import cc.ginpika.minis.coding.workspace.WorkspaceProjects;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/** Detached process groups make workspace SSH/Docker calls short and cancellable. */
@Component
public class WorkspaceCommands {
    private static final class Job {
        final String id,command;final long started=System.currentTimeMillis();
        final int cap;boolean running=true,imported;String output="";Integer exit;
        Job(String id,String command,int cap){this.id=id;this.command=command;this.cap=cap;}
    }
    private final WorkspaceProjects projects;
    private final TerminalService terminal;
    private final TerminalStore terminals;
    private final CodingStore store;
    private final CodingWorkspace files;
    private final CodingProps props;
    private final ObjectMapper json;
    private final Map<String,Map<String,Job>> jobs=new ConcurrentHashMap<>();
    public WorkspaceCommands(WorkspaceProjects projects,TerminalService terminal,TerminalStore terminals,
            CodingStore store,CodingWorkspace files,CodingProps props,ObjectMapper json){
        this.projects=projects;this.terminal=terminal;this.terminals=terminals;this.store=store;this.files=files;this.props=props;this.json=json;
    }
    public Map<String,Object> start(Run run,String command,int seconds){
        var binding=projects.binding(run.repositoryId());
        String rejected=terminal.commandRejection(binding.workspaceId(),command);if(rejected!=null){throw new IllegalArgumentException(rejected);}
        var options=terminal.executionOptions(binding.workspaceId());
        seconds=(int)Math.max(1,Math.min(seconds,options.timeoutMs()/1000));
        var current=jobs.computeIfAbsent(run.id(),ignored->new ConcurrentHashMap<>());
        if(current.values().stream().anyMatch(j->j.running)){throw new IllegalArgumentException("工作空间内同一任务一次运行一个命令；先等待或停止当前命令");}
        String id=UUID.randomUUID().toString(), path=projects.stage(run)+"/jobs/"+id;
        Job job=new Job(id,command,Math.min(props.getMaxOutputChars(),options.maxOutputChars()));current.put(id,job);
        try{
            projects.pushExecution(run);
            // Persist before dispatch. Retrying launch sees the reserved directory and cannot run twice.
            var ids=new ArrayList<String>();Path marker=files.task(run.id()).resolve("workspace-processes.json");
            if(Files.exists(marker)){for(var row:json.readTree(marker.toFile())){ids.add(row.asText());}}
            ids.add(projects.stage(run)+"/jobs/"+id);json.writeValue(marker.toFile(),ids);
            String checkout=projects.stage(run)+"/checkout";
            String inner="cd -- "+q(checkout)+" || exit 72; timeout --foreground -k 2 "+seconds+" sh -lc "+q(command)+"; code=$?; printf '%s' \"$code\" > "+q("../jobs/"+id+"/exit");
            // The job shell starts from the workspace root; cwd may be changed by the raw command only inside its child.
            String launch="command -v setsid >/dev/null; command -v timeout >/dev/null; mkdir -p -- "+q(projects.stage(run)+"/jobs")+"; "
                    +"if ! mkdir -- "+q(path)+"; then echo '命令已受理或结果未知，拒绝重放' >&2; exit 74; fi; "
                    +"setsid sh -c "+q(inner)+" > "+q(path+"/output")+" 2>&1 < /dev/null & pid=$!; printf '%s' \"$pid\" > "+q(path+"/pid");
            projects.execute(run,launch,8000,15000,true);
            store.event(run.id(),"process.started",Map.of("processId",id,"command",command,"runner","WORKSPACE"));
            return read(run,id,0);
        }catch(Exception error){job.running=false;throw new IllegalStateException("工作空间命令启动未确认，请核查进程记录："+error.getMessage(),error);}
    }
    public Map<String,Object> read(Run run,String id,int offset){
        Job job=jobs.getOrDefault(run.id(),Map.of()).get(id);if(job==null){throw new IllegalArgumentException("进程不属于当前任务或后端已重启");}
        synchronized(job){
            if(job.running){
                String path=projects.stage(run)+"/jobs/"+id;
                String script="if [ -f "+q(path+"/exit")+" ]; then cat "+q(path+"/exit")+"; else printf 'RUNNING'; fi; printf '\\n'; head -c "+job.cap+" "+q(path+"/output");
                String output=projects.execute(run,script,job.cap+256,15000,true);int split=output.indexOf('\n');
                String status=split<0?output:output.substring(0,split);String text=split<0?"":output.substring(split+1);
                if(text.length()>job.output.length()){store.event(run.id(),"process.output",Map.of("processId",id,"text",text.substring(job.output.length())));}
                job.output=text;
                if(!status.equals("RUNNING")){
                    job.exit=Integer.parseInt(status.strip());job.running=false;
                    try{projects.pullExecution(run);job.imported=true;}catch(Exception error){throw new IllegalStateException("命令已结束，但代码同步未确认："+error.getMessage(),error);}
                    store.event(run.id(),"process.finished",Map.of("processId",id,"exitCode",job.exit,"timedOut",job.exit==124));
                    audit(run,job);
                }
            }
            int from=Math.max(0,Math.min(offset,job.output.length()));
            return Map.of("processId",id,"running",job.running,"exitCode",job.exit==null?"UNKNOWN":job.exit,"output",job.output.substring(from),"nextOffset",job.output.length(),"truncated",job.output.length()>=job.cap,"timedOut",Objects.equals(job.exit,124));
        }
    }
    public Map<String,Object> await(Run run,String id,AtomicBoolean stopped){
        for(;;){
            if(stopped.get() || Thread.currentThread().isInterrupted()){throw new CancellationException("任务已停止");}
            var state=read(run,id,0);if(!Boolean.TRUE.equals(state.get("running"))){return state;}
            try{Thread.sleep(400);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new CancellationException("任务已停止");}
        }
    }
    public boolean running(String runId){return jobs.getOrDefault(runId,Map.of()).values().stream().anyMatch(j->j.running);}
    public void stopAll(Run run){
        Path marker=files.task(run.id()).resolve("workspace-processes.json");if(!Files.isRegularFile(marker)){return;}
        try{
            for(var row:json.readTree(marker.toFile())){
                String path=row.asText();
                if(!path.matches("\\.minis-coding/"+run.id()+"/[0-9]+/jobs/[a-f0-9-]{36}")){throw new IllegalStateException("工作空间进程记录无效");}
                String script="if [ -f "+q(path+"/pid")+" ] && [ ! -f "+q(path+"/STOPPED")+" ]; then pid=$(cat "+q(path+"/pid")+"); case \"$pid\" in ''|*[!0-9]*) exit 75;; esac; "
                        +"if [ -r \"/proc/$pid/cmdline\" ]; then identity=$(tr '\\000' ' ' < \"/proc/$pid/cmdline\"); if [ -n \"$identity\" ]; then printf '%s' \"$identity\" | grep -F -- "+q(path.substring(path.lastIndexOf('/')+1))+" >/dev/null || { echo '进程身份变化，拒绝停止' >&2; exit 77; }; fi; fi; "
                        +"kill -TERM -- -\"$pid\" 2>/dev/null || true; sleep 0.2; kill -KILL -- -\"$pid\" 2>/dev/null || true; "
                        +"ps -eo pgid=,stat= > "+q(path+"/group-state")+"; if awk -v group=\"$pid\" '$1 == group && $2 !~ /^Z/ { found=1 } END { exit found?0:1 }' "+q(path+"/group-state")+"; then echo '进程组停止未确认' >&2; exit 76; fi; touch "+q(path+"/STOPPED")+"; fi";
                projects.execute(run,script,8000,15000,false);
            }
            if(jobs.getOrDefault(run.id(),Map.of()).isEmpty()){
                var rows=json.readTree(marker.toFile());
                if(!rows.isEmpty()){String last=rows.get(rows.size()-1).asText();projects.pullExecution(run,last.substring(0,last.indexOf("/jobs/"))+"/checkout");}
            }else{
                projects.pullExecution(run);
                for(Job job:jobs.getOrDefault(run.id(),Map.of()).values()){job.running=false;job.imported=true;}
            }
            Files.delete(marker);
        }catch(Exception error){throw new IllegalStateException("工作空间进程清理或代码同步未确认："+error.getMessage(),error);}
    }
    public void recover(Run run){stopAll(run);}
    public void forget(Run run){jobs.remove(run.id());}
    private void audit(Run run,Job job){
        var b=projects.binding(run.repositoryId());var t=terminals.targetById(b.workspaceId()).orElseThrow();
        terminals.insertCommand(new TerminalStore.CommandRow(null,t.id(),t.name(),job.command,projects.stage(run)+"/checkout",job.exit,System.currentTimeMillis()-job.started,job.output,"",job.output.length()>=job.cap,Objects.equals(job.exit,124),Objects.equals(job.exit,0),"coding",System.currentTimeMillis()));
    }
    private static String q(String text){return WorkspaceProjects.q(text);}
}
