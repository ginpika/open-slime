package cc.ginpika.minis.coding.runner;

import cc.ginpika.minis.coding.api.CodingDtos.*;
import cc.ginpika.minis.coding.config.CodingProps;
import cc.ginpika.minis.coding.repository.CodingWorkspace;
import cc.ginpika.minis.coding.store.CodingStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/** Model commands are isolated in a no-network container by default. Host shell is opt-in. */
@Component
public class CodingProcesses {
    private final CodingProps props;
    private final CodingWorkspace workspace;
    private final CodingStore store;
    private final WorkspaceCommands workspaceCommands;
    private final ExecutorService io=Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentMap<String,ConcurrentMap<String,Job>> runs=new ConcurrentHashMap<>();
    private final ConcurrentMap<String,String> containers=new ConcurrentHashMap<>();
    private final ConcurrentMap<String,Object> locks=new ConcurrentHashMap<>();
    private static final class Job {
        final String id; final Process process; final StringBuilder output=new StringBuilder();
        final long started=System.currentTimeMillis();
        volatile Integer exit; volatile boolean truncated, timedOut;
        final CompletableFuture<Void> drained=new CompletableFuture<>();
        Job(String id,Process process){ this.id=id; this.process=process; }
    }
    public CodingProcesses(CodingProps props,CodingWorkspace workspace,CodingStore store,WorkspaceCommands workspaceCommands){ this.props=props; this.workspace=workspace; this.store=store; this.workspaceCommands=workspaceCommands; }
    public Map<String,Object> start(Run run,String command,int seconds) {
        if(run.mode()!=Mode.EXECUTE) { throw new IllegalArgumentException("本任务未授权命令执行，请创建 EXECUTE 任务"); }
        if(command==null || command.isBlank() || command.length()>16_000 || command.indexOf('\0')>=0) { throw new IllegalArgumentException("命令为空或过长"); }
        seconds=Math.min(props.getMaxProcessSeconds(),Math.max(1,seconds));
        if(run.runner()==Runner.WORKSPACE){return workspaceCommands.start(run,command,seconds);}
        synchronized(locks.computeIfAbsent(run.id(),ignored->new Object())) {
            var jobs=runs.computeIfAbsent(run.id(),ignored->new ConcurrentHashMap<>());
            if(jobs.values().stream().filter(j->j.process.isAlive()).count()>=4) { throw new IllegalArgumentException("最多同时运行 4 个进程"); }
            List<String> args;
            if(run.runner()==Runner.LOCAL) {
                if(!props.isAllowLocalExecution()) { throw new IllegalArgumentException("服务器未启用本机命令执行；请使用 Docker Runner"); }
                args=System.getProperty("os.name").startsWith("Windows")
                        ? List.of("powershell.exe","-NoLogo","-NoProfile","-NonInteractive","-Command",command)
                        : List.of("sh","-lc",command);
            } else {
                ensureContainer(run);
                args=List.of("docker","exec","--workdir","/workspace",container(run),"timeout","-k","2",Integer.toString(seconds),"sh","-lc",command);
            }
            try {
                Process process=HostCommands.builder(args,workspace.checkout(run.id())).redirectErrorStream(true).start();
                String id=UUID.randomUUID().toString(); Job job=new Job(id,process); jobs.put(id,job);
                store.event(run.id(),"process.started",Map.of("processId",id,"command",command,"runner",run.runner().name()));
                io.execute(()->drain(run,job));
                int timeout=seconds;
                io.execute(()->{
                    try {
                        if(!process.waitFor(timeout+5L,TimeUnit.SECONDS)) { job.timedOut=true; stop(run,id); }
                        job.exit=process.waitFor();
                        job.timedOut|=job.exit==124;
                        store.event(run.id(),"process.finished",Map.of("processId",id,"exitCode",job.exit,"timedOut",job.timedOut));
                    } catch(Exception e){ job.exit=-1; store.event(run.id(),"process.error",Map.of("processId",id,"error",String.valueOf(e.getMessage()))); }
                });
                return read(run.id(),id,0);
            } catch(Exception e){ throw new IllegalStateException("启动命令失败："+e.getMessage(),e); }
        }
    }
    private void drain(Run run,Job job) {
        Path log=workspace.task(run.id()).resolve("process-"+job.id+".log");
        try(var reader=new java.io.InputStreamReader(job.process.getInputStream(),StandardCharsets.UTF_8);
            var writer=Files.newBufferedWriter(log,StandardCharsets.UTF_8)) {
            char[] buffer=new char[4096]; int count;
            while((count=reader.read(buffer))!=-1) {
                String chunk;
                synchronized(job) {
                    int keep=Math.min(count,Math.max(0,props.getMaxOutputChars()-job.output.length()));
                    chunk=new String(buffer,0,keep); job.output.append(chunk); job.truncated|=keep<count;
                }
                if(!chunk.isEmpty()){ writer.write(chunk); writer.flush(); store.event(run.id(),"process.output",Map.of("processId",job.id,"text",chunk)); }
            }
        } catch(Exception e){ job.drained.completeExceptionally(e); store.event(run.id(),"process.error",Map.of("processId",job.id,"error",String.valueOf(e.getMessage()))); }
        finally { job.drained.complete(null); }
    }
    public Map<String,Object> read(String runId,String id,int offset) {
        if(store.runRunner(runId)==Runner.WORKSPACE){return workspaceCommands.read(store.runInternal(runId),id,offset);}
        Job job=runs.getOrDefault(runId,new ConcurrentHashMap<>()).get(id);
        if(job==null){ throw new IllegalArgumentException("进程不存在或后端已重启；请检查持久日志与未知操作"); }
        synchronized(job){ int from=Math.max(0,Math.min(offset,job.output.length())); return Map.of("processId",id,"running",job.exit==null || job.process.isAlive(),"exitCode",job.exit==null?"UNKNOWN":job.exit,"output",job.output.substring(from),"nextOffset",job.output.length(),"truncated",job.truncated,"timedOut",job.timedOut); }
    }
    public Map<String,Object> await(Run run,String id,AtomicBoolean stopped) {
        if(run.runner()==Runner.WORKSPACE){return workspaceCommands.await(run,id,stopped);}
        Job job=runs.getOrDefault(run.id(),new ConcurrentHashMap<>()).get(id);
        if(job==null){ throw new IllegalArgumentException("进程不存在"); }
        while(job.exit==null){
            if(stopped.get() || Thread.currentThread().isInterrupted()){ throw new CancellationException("任务已停止"); }
            try{ Thread.sleep(100); }catch(InterruptedException e){ Thread.currentThread().interrupt(); throw new CancellationException("任务已停止"); }
        }
        try{job.drained.get(3,TimeUnit.SECONDS);}catch(Exception e){throw new IllegalStateException("进程输出尚未确认完整",e);}
        return read(run.id(),id,0);
    }
    public Map<String,Object> stop(Run run,String id) {
        if(run.runner()==Runner.WORKSPACE){workspaceCommands.read(run,id,0);workspaceCommands.stopAll(run);return Map.of("stopped",true,"scope","task_process_group");}
        if(!runs.getOrDefault(run.id(),new ConcurrentHashMap<>()).containsKey(id)){ throw new IllegalArgumentException("进程不存在"); }
        // A Docker exec client cannot kill its remote children reliably. Stop the whole task sandbox.
        stopAll(run);
        return Map.of("stopped",true,"scope","task_sandbox","message","本任务沙箱中的全部进程已请求停止");
    }
    public boolean running(String id){ return workspaceCommands.running(id) || runs.getOrDefault(id,new ConcurrentHashMap<>()).values().stream().anyMatch(j->j.exit==null || j.process.isAlive()); }
    public void stopAll(Run run) {
        if(run.runner()==Runner.WORKSPACE){workspaceCommands.stopAll(run);return;}
        synchronized(locks.computeIfAbsent(run.id(),ignored->new Object())) {
            if(run.runner()==Runner.DOCKER && !containers.containsKey(run.id())
                    && Files.isRegularFile(workspace.task(run.id()).resolve("runner-container.txt"))){
                recover(run);
            }
            if(run.runner()==Runner.DOCKER && container(run).equals(containers.get(run.id()))) {
                var result=HostCommands.run(List.of("docker","rm","--force",container(run)),null,10,10_000);
                if(result.exitCode()!=0 && !result.stderr().contains("No such container")){ throw new IllegalStateException("无法确认沙箱停止："+result.stderr()); }
                containers.remove(run.id(),container(run));
                try{Files.deleteIfExists(workspace.task(run.id()).resolve("runner-container.txt"));}catch(Exception e){throw new IllegalStateException("清理 Runner 状态失败",e);}
            }
            for(Job job:runs.getOrDefault(run.id(),new ConcurrentHashMap<>()).values()){
                if(job.process.isAlive()){
                    HostCommands.stop(job.process);
                    try{if(!job.process.waitFor(2,TimeUnit.SECONDS)){throw new IllegalStateException("进程终止尚未确认");}}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("进程终止确认被中断",e);}
                }
            }
        }
    }
    public void recover(Run run) {
        if(run.runner()==Runner.WORKSPACE){workspaceCommands.recover(run);return;}
        if(run.runner()!=Runner.DOCKER){ return; }
        Path marker=workspace.task(run.id()).resolve("runner-container.txt");
        if(!Files.isRegularFile(marker)){return;}
        try{
            String old=Files.readString(marker).strip();
            if(!old.matches("minis-coding-"+run.id()+"-[0-9]+")){throw new IllegalStateException("Runner 状态记录无效");}
            var result=HostCommands.run(List.of("docker","rm","--force",old),null,10,10_000);
            if(result.exitCode()!=0 && !result.stderr().contains("No such container")){ throw new IllegalStateException("恢复前无法确认旧沙箱已停止："+result.stderr()); }
            Files.deleteIfExists(marker);
        }catch(java.io.IOException e){throw new IllegalStateException("恢复 Runner 状态失败",e);}
    }
    private String container(Run run){ return "minis-coding-"+run.id()+"-"+run.attempt(); }
    private void ensureContainer(Run run) {
        if(container(run).equals(containers.get(run.id()))){ return; }
        Path dir=workspace.checkout(run.id());
        String bind=props.getDockerHostDataDir()==null || props.getDockerHostDataDir().isBlank()?dir.toString():props.getDockerHostDataDir().replace('\\','/').replaceAll("/+$","")+"/"+workspace.root().relativize(dir).toString().replace('\\','/');
        if(bind.contains(",")){throw new IllegalArgumentException("Docker 挂载路径不能包含逗号");}
        String user="1000:1000";
        try { user=Files.getAttribute(dir,"unix:uid")+":"+Files.getAttribute(dir,"unix:gid"); }catch(Exception ignored){ /* Docker Desktop bind mounts use their own permissions. */ }
        try{Files.writeString(workspace.task(run.id()).resolve("runner-container.txt"),container(run));}catch(Exception e){throw new IllegalStateException(e);}
        var create=HostCommands.run(List.of("docker","create","--pull=never","--name",container(run),"--network","none",
                "--read-only","--cap-drop","ALL","--security-opt","no-new-privileges","--memory","1g","--cpus","2","--pids-limit","128",
                "--user",user,"--tmpfs","/tmp:rw,nosuid,nodev,size=256m","--env","HOME=/tmp","--workdir","/workspace",
                "--mount","type=bind,source="+bind+",target=/workspace",props.getDockerImage(),"sh","-c","sleep infinity"),null,20,20_000);
        if(create.exitCode()!=0){ throw new IllegalStateException("Docker Runner 不可用，请准备镜像 "+props.getDockerImage()+"："+create.stderr()); }
        containers.put(run.id(),container(run));
        var start=HostCommands.run(List.of("docker","start",container(run)),null,15,10_000);
        if(start.exitCode()!=0){ throw new IllegalStateException("沙箱启动失败："+start.stderr()); }
    }
    public void forget(Run run){workspaceCommands.forget(run);runs.remove(run.id());locks.remove(run.id());}
    @jakarta.annotation.PreDestroy public void close(){
        for(var jobs:runs.values()){ for(Job job:jobs.values()){ if(job.process.isAlive()){ HostCommands.stop(job.process); } } }
        io.shutdownNow();
        // Containers are named per durable run; interrupted-run recovery removes leftovers explicitly.
    }
}
