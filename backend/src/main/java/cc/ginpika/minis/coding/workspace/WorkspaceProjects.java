package cc.ginpika.minis.coding.workspace;

import cc.ginpika.minis.agent.workspace.WorkspaceDtos;
import cc.ginpika.minis.agent.workspace.WorkspaceService;
import cc.ginpika.minis.ai.terminal.ExecTarget;
import cc.ginpika.minis.ai.terminal.store.TerminalStore;
import cc.ginpika.minis.coding.api.CodingDtos.*;
import cc.ginpika.minis.coding.config.CodingProps;
import cc.ginpika.minis.coding.repository.CodingWorkspace;
import cc.ginpika.minis.coding.store.CodingStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Workspace identity/credentials are owned by the existing terminal domain, never copied here. */
@Service
public class WorkspaceProjects {
    public record Binding(String repositoryId,String workspaceId,String directory,String root,String fingerprint) {}
    private final TerminalStore terminals;
    private final WorkspaceService browser;
    private final Map<String,ExecTarget> targets;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CodingStore store;
    private final CodingWorkspace files;
    private final CodingProps props;
    private final ObjectMapper json;
    private final Map<String,Object> locks=new java.util.concurrent.ConcurrentHashMap<>();
    public WorkspaceProjects(TerminalStore terminals,WorkspaceService browser,List<ExecTarget> targets,
            JdbcTemplate jdbc,TransactionTemplate tx,CodingStore store,CodingWorkspace files,CodingProps props,ObjectMapper json){
        this.terminals=terminals;this.browser=browser;this.jdbc=jdbc;this.tx=tx;this.store=store;this.files=files;this.props=props;this.json=json;
        Map<String,ExecTarget> map=new HashMap<>();targets.forEach(t->map.put(t.kind(),t));this.targets=Map.copyOf(map);
    }
    public List<Workspace> workspaces(){return terminals.targets().stream().map(t->new Workspace(t.id(),t.name(),t.kind(),t.enabled(),t.workdir())).toList();}
    public Binding binding(String repositoryId){
        return jdbc.query("SELECT * FROM coding_workspace_project WHERE repository_id=?",(r,n)->new Binding(r.getString("repository_id"),r.getString("workspace_id"),r.getString("directory"),r.getString("root_path"),r.getString("target_fingerprint")),repositoryId).stream().findFirst().orElse(null);
    }
    public boolean bound(Run run){return binding(run.repositoryId())!=null;}
    public ExecTarget.Shell openShell(String owner,String projectId,int cols,int rows) throws java.io.IOException {
        project(owner,projectId); // Ownership must be checked before resolving credentials.
        Binding b=binding(projectId);var target=boundTarget(b,true);
        String prefix=target.workdir()==null || target.workdir().isBlank()?"":"cd -- "+q(target.workdir())+" || exit 72; ";
        checked(target,guard(b.directory(),b.root())+"command -v bash >/dev/null 2>&1 || { echo '工作空间未安装 bash，请先安装 bash 后再创建终端' >&2; exit 127; }",8000,15000);
        String command=prefix+guard(b.directory(),b.root())+"exec bash -i";
        return targets.get(target.kind()).openShell(target,command,cols,rows);
    }
    public List<CodingProject> projects(String owner){return store.repositories(owner).stream().filter(r->binding(r.id())!=null).map(r->project(owner,r.id())).toList();}
    /** Only hides the recent entry; bindings, runs and workspace files remain available. */
    public void removeRecent(String owner,String id){
        project(owner,id);
        jdbc.update("INSERT OR IGNORE INTO coding_hidden_recent_project(repository_id) VALUES(?)",id);
    }
    public CodingProject project(String owner,String id){
        Repository repo=store.repository(id,owner);Binding binding=binding(id);
        if(repo==null || binding==null){throw new ResponseStatusException(HttpStatus.NOT_FOUND,"编程项目不存在");}
        var target=terminals.targetById(binding.workspaceId()).orElse(null);
        boolean recentHidden=jdbc.queryForObject("SELECT COUNT(*) FROM coding_hidden_recent_project WHERE repository_id=?",Integer.class,id)>0;
        return new CodingProject(id,repo.name(),binding.workspaceId(),target==null?"工作空间已删除":target.name(),target==null?"unknown":target.kind(),binding.directory(),repo.validationCommand(),target!=null && target.enabled() && fingerprint(target).equals(binding.fingerprint()),repo.createdAt(),recentHidden);
    }
    public CodingProject open(String owner,OpenProject req){
        if(req==null){throw bad("请选择工作空间与项目目录");}
        var target=target(req.workspaceId(),true);
        String directory=WorkspaceArchive.relative(req.directory());
        if(req.validationCommand()!=null && req.validationCommand().length()>16000){throw bad("验证命令过长");}
        String name=req.name()==null?"":req.name().trim();
        if(req.create()){
            if(name.isBlank() || name.length()>100 || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\") || name.contains(":" ) || name.chars().anyMatch(c->c<32)){
                throw bad("项目名需为有效的单个目录名称");
            }
            String parent=directory;
            WorkspaceArchive.relative(name);
            String root=checked(target,guard(parent,null)+"mkdir -- "+q(name)+"; cd -- "+q(name)+"; pwd -P",8000,20000).strip();
            directory=parent.equals(".")?name:parent+"/"+name;
            return bind(owner,target,directory,root,name,req.validationCommand());
        }
        String root=checked(target,guard(directory,null)+"pwd -P",8000,20000).strip();
        if(name.isBlank()){name=directory.equals(".")?target.name():directory.substring(directory.lastIndexOf('/')+1);}
        if(name.length()>100){throw bad("项目名过长");}
        return bind(owner,target,directory,root,name,req.validationCommand());
    }
    private CodingProject bind(String owner,TerminalStore.TargetRow target,String directory,String root,String name,String validation){
        String address="workspace://"+target.id()+"/"+directory;
        synchronized(locks.computeIfAbsent(owner+":"+address,ignored->new Object())){
            var existing=store.repositories(owner).stream().filter(r->r.path().equals(address)).findFirst().orElse(null);
            if(existing!=null){
                jdbc.update("DELETE FROM coding_hidden_recent_project WHERE repository_id=?",existing.id());
                return project(owner,existing.id());
            }
            return tx.execute(status->{
                Repository repo=store.register(owner,name,address,null,validation);
                jdbc.update("INSERT INTO coding_workspace_project VALUES(?,?,?,?,?)",repo.id(),target.id(),directory,root,fingerprint(target));
                return project(owner,repo.id());
            });
        }
    }
    public WorkspaceDtos.DirView list(String owner,String projectId,String path,String runId){
        CodingProject project=project(owner,projectId);String relative=WorkspaceArchive.relative(path);
        if(runId!=null && !runId.isBlank()){
            Run run=requireRun(owner,projectId,runId);
            try{
                Path base=relative.equals(".")?files.checkout(run.id()):files.resolve(files.checkout(run.id()),relative,false);
                var entries=new ArrayList<WorkspaceDtos.FileEntry>();
                try(var paths=Files.list(base)){
                    for(Path p:paths.toList()){
                        String name=p.getFileName().toString(), full=relative.equals(".")?name:relative+"/"+name;
                        if(CodingWorkspace.excluded(full) || Files.isSymbolicLink(p)){continue;}
                        p=files.resolve(files.checkout(run.id()),full,false);
                        entries.add(new WorkspaceDtos.FileEntry(name,Files.isDirectory(p),Files.isDirectory(p)?0:Files.size(p),"",false));
                    }
                }
                entries.sort(Comparator.comparing(WorkspaceDtos.FileEntry::dir).reversed().thenComparing(WorkspaceDtos.FileEntry::name));
                return new WorkspaceDtos.DirView(relative,entries);
            }catch(Exception e){throw bad("任务副本尚不可浏览："+e.getMessage());}
        }
        verify(binding(projectId),relative);
        var view=browser.list(project.workspaceId(),join(project.directory(),relative));
        return new WorkspaceDtos.DirView(relative,view.entries().stream().filter(e->!e.link() && !CodingWorkspace.excluded(join(relative,e.name()))).toList());
    }
    public WorkspaceDtos.FileView read(String owner,String projectId,String path,String runId){
        CodingProject project=project(owner,projectId);String relative=WorkspaceArchive.relative(path);if(relative.equals(".")){throw bad("请选择文件");}
        if(runId!=null && !runId.isBlank()){
            Run run=requireRun(owner,projectId,runId);
            try{
                Path p=files.resolve(files.checkout(run.id()),relative,false);long size=Files.size(p);
                byte[] bytes;try(var stream=Files.newInputStream(p)){bytes=stream.readNBytes(Math.min(props.getMaxFileBytes(),200000));}
                boolean binary=CodingWorkspace.binary(bytes);
                return new WorkspaceDtos.FileView(relative,p.getFileName().toString(),size,size>bytes.length,binary,binary?null:new String(bytes,StandardCharsets.UTF_8));
            }catch(Exception e){throw bad(e.getMessage());}
        }
        verify(binding(projectId),relative);return browser.read(project.workspaceId(),join(project.directory(),relative));
    }
    private Run requireRun(String owner,String projectId,String runId){Run run=store.run(runId,owner);if(run==null || !run.repositoryId().equals(projectId)){throw bad("任务不属于当前项目");}return run;}
    public synchronized void prepare(Run run) throws Exception {
        files.requireConsistent(run.id());
        if(Files.isRegularFile(files.task(run.id()).resolve("baseline.json"))){verify(binding(run.repositoryId()));return;}
        var snapshot=snapshot(binding(run.repositoryId()),null);
        files.prepareImported(run.id(),snapshot.files(),snapshot.executable());
        json.writeValue(files.task(run.id()).resolve("workspace-origin.json").toFile(),snapshot.executable());
    }
    public String execute(Run run,String command,int cap,long timeout,boolean enabled){
        Binding b=binding(run.repositoryId());var target=boundTarget(b,enabled);
        return checked(target,guard(b.directory(),b.root())+"cd -- \"$base\"; "+command,cap,timeout);
    }
    public String stage(Run run){return ".minis-coding/"+run.id()+"/"+run.attempt();}
    public void pushExecution(Run run) throws Exception {
        Map<String,byte[]> content=new TreeMap<>();Set<String> modes=new HashSet<>();
        for(var item:files.manifest(run.id()).entrySet()){
            content.put(item.getKey(),Files.readAllBytes(files.resolve(files.checkout(run.id()),item.getKey(),false)));
            if(item.getValue().executable()){modes.add(item.getKey());}
        }
        String stage=stage(run), transfer=stage+"/transfer-"+UUID.randomUUID();
        upload(run,transfer,WorkspaceArchive.pack(content,modes));
        // Delete only source paths from the previous manifest; preserve dependencies and build outputs.
        StringBuilder sync=new StringBuilder("set -eu\n");
        Path previous=files.task(run.id()).resolve("workspace-synced.json");
        if(Files.isRegularFile(previous)){
            for(var node:json.readTree(previous.toFile())){
                String name=WorkspaceArchive.relative(node.asText());if(!content.containsKey(name)){sync.append(safeParents(stage+"/checkout",name)).append("rm -f -- ").append(q(stage+"/checkout/"+name)).append('\n');}
            }
        }
        for(String name:content.keySet()){sync.append(safeParents(stage+"/checkout",name));}
        sync.append("tar -xzf ").append(q(transfer+"/payload.tar.gz")).append(" -C ").append(q(stage+"/checkout"));
        execute(run,"mkdir -p -- "+q(stage+"/checkout"),8000,20000,true);
        uploadScript(run,transfer+"/sync.sh",sync.toString());execute(run,"sh "+q(transfer+"/sync.sh"),8000,30000,true);
        json.writeValue(previous.toFile(),content.keySet());
    }
    public void pullExecution(Run run) throws Exception {
        pullExecution(run,stage(run)+"/checkout");
    }
    public void pullExecution(Run run,String checkout) throws Exception {
        var snapshot=snapshot(binding(run.repositoryId()),checkout);
        files.replaceImported(run.id(),snapshot.files(),snapshot.executable());
    }
    public synchronized boolean integrate(Run run) throws Exception {
        var current=snapshot(binding(run.repositoryId()),null);
        return files.integrateImported(run.id(),current.files(),current.executable());
    }
    public synchronized Diff accept(Run run,String hash) throws Exception {
        Binding b=binding(run.repositoryId());Diff diff=files.diff(run.id());
        if(!diff.artifactHash().equals(hash)){throw bad("产物已变化，请重新审阅");}
        String stage=stage(run)+"/accept-"+hash;
        // A durable remote receipt makes a lost response safe to retry without applying twice.
        String receipt=execute(run,"if [ -f "+q(stage+"/DONE")+" ]; then cat "+q(stage+"/DONE")+"; fi",8000,15000,true).strip();
        if(receipt.equals(hash)){if(Files.exists(files.task(run.id()).resolve("accept-journal.json"))){Files.move(files.task(run.id()).resolve("accept-journal.json"),files.task(run.id()).resolve("accepted.json"),StandardCopyOption.REPLACE_EXISTING);}return diff;}
        if(Files.exists(files.task(run.id()).resolve("accept-journal.json"))){throw bad("上次接受结果未确认，请核查工作空间接受日志");}
        var current=snapshot(b,null);var baseline=files.baseline(run.id());
        var stamps=new TreeMap<String,CodingWorkspace.FileStamp>();
        current.files().forEach((name,bytes)->stamps.put(name,new CodingWorkspace.FileStamp(CodingWorkspace.hash(bytes),bytes.length,current.executable().contains(name))));
        if(files.matchesApplied(run.id(),diff,stamps)){
            // Snapshot evidence alone is insufficient: recheck live paths before confirming the receipt.
            var expected=files.appliedManifest(run.id(),diff);
            var confirm=new StringBuilder("set -eu\ncd -- ").append(q(b.root())).append('\n');
            for(var entry:expected.entrySet()){
                String path="./"+entry.getKey();confirm.append(safeParents(".",entry.getKey()))
                        .append("test -f ").append(q(path)).append("\ntest \"$(sha256sum ").append(q(path)).append(" | cut -d ' ' -f1)\" = ").append(q(entry.getValue().hash())).append('\n')
                        .append(entry.getValue().executable()?"test -x ":"test ! -x ").append(q(path)).append('\n');
            }
            for(Change change:diff.changes())if("MISSING".equals(change.afterHash()))confirm.append(safeParents(".",change.path())).append("test ! -e ").append(q("./"+change.path())).append('\n');
            execute(run,confirm.toString(),8000,30000,true);files.confirmApplied(run.id(),diff);return diff;
        }
        if(!baseline.equals(stamps))throw bad("项目基准已变化，请先整合并重新 verify；拒绝覆盖");
        for(var entry:baseline.entrySet()){
            byte[] value=current.files().get(entry.getKey());if(value==null || !CodingWorkspace.hash(value).equals(entry.getValue().hash()) || current.executable().contains(entry.getKey())!=entry.getValue().executable()){throw bad("项目目录已变化："+entry.getKey());}
        }
        for(Change change:diff.changes()){if(change.beforeHash().equals("MISSING") && current.files().containsKey(change.path())){throw bad("项目中已出现同名文件："+change.path());}}
        Map<String,byte[]> payload=new TreeMap<>();Set<String> modes=new HashSet<>();StringBuilder script=new StringBuilder("set -eu\n");
        script.append("cd -- ").append(q(b.root())).append("\n");
        for(var entry:baseline.entrySet()){
            script.append(safeParents(".",entry.getKey())).append("test -f ").append(q("./"+entry.getKey())).append("\ntest \"$(sha256sum ").append(q("./"+entry.getKey())).append(" | cut -d ' ' -f1)\" = ").append(q(entry.getValue().hash())).append("\n")
                    .append(entry.getValue().executable()?"test -x ":"test ! -x ").append(q("./"+entry.getKey())).append('\n');
        }
        // Stage is resolved independently in the workspace root and passed as an absolute path.
        execute(run,safeParents(".",stage+"/DONE")+"mkdir -p -- "+q(stage),8000,15000,true);
        String absoluteStage=execute(run,"cd -- "+q(stage)+"; pwd -P",8000,15000,true).strip();
        var rollback=new StringBuilder();int index=0;
        for(Change change:diff.changes()){
            String name=change.path();script.append(safeParents(".",name));
            if(change.beforeHash().equals("MISSING")){script.append("test ! -e ").append(q("./"+name)).append("\n");}
            if(!change.afterHash().equals("MISSING")){payload.put("new/"+name,Files.readAllBytes(files.resolve(files.checkout(run.id()),name,false)));if(change.executable()){modes.add("new/"+name);}}
            if(!change.beforeHash().equals("MISSING")){payload.put("old/"+name,Files.readAllBytes(files.resolve(files.task(run.id()).resolve("baseline"),name,false)));if(current.executable().contains(name)){modes.add("old/"+name);}}
            String mark=absoluteStage+"/applied-"+index++;
            rollback.append("if [ -f ").append(q(mark)).append(" ]; then ");
            if(change.beforeHash().equals("MISSING")){rollback.append("rm -f -- ").append(q("./"+name));}
            else{rollback.append("cp -p -- ").append(q(absoluteStage+"/old/"+name)).append(' ').append(q("./"+name));}
            rollback.append("; fi\n");
        }
        payload.put("rollback.sh",rollback.toString().getBytes(StandardCharsets.UTF_8));
        script.append("rollback() { code=$?; if [ \"$code\" -ne 0 ]; then sh ").append(q(absoluteStage+"/rollback.sh")).append("; fi; exit \"$code\"; }\ntrap rollback EXIT HUP INT TERM\n");
        index=0;
        for(Change change:diff.changes()){
            String name=change.path(), dest="./"+name;script.append("touch ").append(q(absoluteStage+"/applied-"+index++)).append('\n');
            if(change.afterHash().equals("MISSING")){script.append("rm -- ").append(q(dest)).append('\n');}
            else{
                String parent=name.contains("/")?name.substring(0,name.lastIndexOf('/')):".";
                String temporary=(parent.equals(".")?"":parent+"/")+".coding-"+run.id()+"-"+index;
                script.append(safeParents(".",temporary)).append("test ! -e ").append(q("./"+temporary)).append('\n');
                script.append("mkdir -p -- ").append(q("./"+parent)).append("\ncp -p -- ").append(q(absoluteStage+"/new/"+name)).append(' ').append(q("./"+temporary)).append("\nmv -f -- ").append(q("./"+temporary)).append(' ').append(q(dest)).append('\n');
            }
        }
        script.append("printf '%s' ").append(q(hash)).append(" > ").append(q(absoluteStage+"/DONE")).append("\ntrap - EXIT HUP INT TERM\n");
        payload.put("apply.sh",script.toString().getBytes(StandardCharsets.UTF_8));
        String transfer=stage+"/transfer";upload(run,transfer,WorkspaceArchive.pack(payload,modes));
        execute(run,"tar -xzf "+q(transfer+"/payload.tar.gz")+" -C "+q(stage),8000,30000,true);
        json.writeValue(files.task(run.id()).resolve("accept-journal.json").toFile(),diff);
        execute(run,"sh "+q(stage+"/apply.sh"),8000,60000,true);
        Files.move(files.task(run.id()).resolve("accept-journal.json"),files.task(run.id()).resolve("accepted.json"),StandardCopyOption.REPLACE_EXISTING);
        return diff;
    }
    private WorkspaceArchive.Snapshot snapshot(Binding b,String executionPath) throws Exception {
        var target=boundTarget(b,true);String directory=executionPath==null?b.directory():executionPath;
        StringBuilder command=new StringBuilder(guard(directory,executionPath==null?b.root():null));
        command.append("tmp=$(mktemp); trap 'rm -f -- \"$tmp\"' EXIT; tar ");
        for(String pattern:List.of(".git",".minis-coding","node_modules","target","dist","build",".aws",".ssh",".codex",".idea",".gradle",".env",".env.*","*.pem","*.key","*.p12","*.db","*.db-*","*.vscdb","data/logs","data/backups","data/coding")){
            command.append("--exclude=").append(q(pattern)).append(' ');
        }
        command.append("-czf \"$tmp\" .; test \"$(wc -c < \"$tmp\")\" -le ").append(WorkspaceArchive.TRANSFER_BYTES).append("; base64 < \"$tmp\"");
        String data=checked(target,command.toString(),24*1024*1024,60000);
        return WorkspaceArchive.unpack(Base64.getMimeDecoder().decode(data),props);
    }
    private void upload(Run run,String path,byte[] bytes){
        execute(run,safeParents(".",path+"/part")+"mkdir -p -- "+q(path),8000,20000,true);
        var parts=new ArrayList<String>();int index=0;
        for(int offset=0;offset<bytes.length;offset+=18000){
            String part=path+"/part-"+String.format("%06d",index++);parts.add(part);
            String encoded=Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes,offset,Math.min(bytes.length,offset+18000)));
            execute(run,"printf '%s' "+q(encoded)+" | base64 -d > "+q(part),8000,20000,true);
        }
        execute(run,"cat "+String.join(" ",parts.stream().map(WorkspaceProjects::q).toList())+" > "+q(path+"/payload.tar.gz"),8000,20000,true);
    }
    private void uploadScript(Run run,String path,String script){
        upload(run,path+".parts",script.getBytes(StandardCharsets.UTF_8));
        execute(run,"cp -- "+q(path+".parts/payload.tar.gz")+" "+q(path),8000,20000,true);
    }
    private void verify(Binding b){checked(boundTarget(b,true),guard(b.directory(),b.root())+"true",8000,15000);}
    private void verify(Binding b,String path){checked(boundTarget(b,true),guard(b.directory(),b.root())+safeParents(".",path)+"true",8000,15000);}
    private TerminalStore.TargetRow boundTarget(Binding b,boolean enabled){
        if(b==null){throw bad("项目未绑定工作空间");}var target=target(b.workspaceId(),enabled);
        if(!fingerprint(target).equals(b.fingerprint())){throw bad("工作空间连接配置已变化，请重新核对项目绑定");}return target;
    }
    private TerminalStore.TargetRow target(String id,boolean enabled){
        var target=terminals.targetById(id).orElseThrow(()->bad("工作空间不存在"));
        if(enabled && !target.enabled()){throw bad("工作空间未启用，请先在工作空间页面启用");}
        if(!targets.containsKey(target.kind())){throw bad("工作空间类型不可用");}return target;
    }
    private String checked(TerminalStore.TargetRow target,String command,int cap,long timeout){
        var result=targets.get(target.kind()).run(target,command,new ExecTarget.ExecOptions(target.workdir(),timeout,cap));
        if(result.exitCode()!=0 || result.timedOut() || result.truncated()){
            throw bad("工作空间操作未完成："+(result.stderr()==null || result.stderr().isBlank()?"退出码 "+result.exitCode():result.stderr()));
        }
        return result.stdout()==null?"":result.stdout();
    }
    static String guard(String directory,String expected){
        String rel=directory.matches("\\.minis-coding/[a-f0-9-]{36}/[0-9]+/checkout")?directory:WorkspaceArchive.relative(directory);
        return "set -eu; base=$(pwd -P); cd -- "+q("./"+rel)+"; selected=$(pwd -P); case \"$selected/\" in \"${base%/}/\"*) ;; *) echo '目录越界' >&2; exit 73;; esac; "
                +(expected==null?"":"test \"$selected\" = "+q(expected)+"; ");
    }
    /** Check every existing parent and the leaf before writing; never follow links. */
    static String safeParents(String base,String name){
        StringBuilder out=new StringBuilder();String path=base;
        for(String part:name.split("/")){
            if(part.isBlank() || part.equals(".")){continue;}if(part.equals("..")){throw bad("路径越界");}
            path+="/"+part;out.append("test ! -L ").append(q(path)).append("\n");
            out.append("if [ -f ").append(q(path)).append(" ]; then test \"$(stat -c %h ").append(q(path)).append(")\" = 1; fi\n");
        }
        return out.toString();
    }
    private static String fingerprint(TerminalStore.TargetRow t) {
        String config = String.join("|", String.valueOf(t.kind()), String.valueOf(t.workdir()),
                String.valueOf(t.dockerHost()), String.valueOf(t.container()), String.valueOf(t.image()),
                String.valueOf(t.sshHost()), String.valueOf(t.sshPort()), String.valueOf(t.sshUser()));
        // Preserve existing project fingerprints when no host directory is bound.
        if (t.hostWorkdir() != null) config += "|hostWorkdir=" + t.hostWorkdir();
        return CodingWorkspace.hash(config.getBytes(StandardCharsets.UTF_8));
    }
    private static String join(String parent,String relative){return parent.equals(".")?relative:relative.equals(".")?parent:parent+"/"+relative;}
    public static String q(String text){return ExecTarget.quote(text);}
    private static ResponseStatusException bad(String text){return new ResponseStatusException(HttpStatus.BAD_REQUEST,text);}
}
