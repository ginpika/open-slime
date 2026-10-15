package cc.ginpika.minis.coding.repository;

import cc.ginpika.minis.coding.api.CodingDtos.*;
import cc.ginpika.minis.coding.config.CodingProps;
import cc.ginpika.minis.coding.runner.HostCommands;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Independent task snapshots. The model never writes into the user's original checkout. */
@Component
public class CodingWorkspace {
    private final CodingProps props;
    private final ObjectMapper json;
    private String databaseUrl="jdbc:sqlite:../data/minis.db";
    private final java.util.concurrent.ConcurrentMap<String,Object> gitLocks=new java.util.concurrent.ConcurrentHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired
    public void setDatabaseUrl(@org.springframework.beans.factory.annotation.Value("${spring.datasource.url}") String value){databaseUrl=value;}
    public record FileStamp(String hash, long size, boolean executable) {}
    public CodingWorkspace(CodingProps props,ObjectMapper json) { this.props=props; this.json=json; }
    public Path root() {
        try {
            String configured=props.getDataDir();
            Path p;
            if(configured==null || configured.isBlank()){
                String database=databaseUrl.replaceFirst("^jdbc:sqlite:","");
                if(database.equals(":memory:") || database.startsWith("file:")){p=Path.of("../data/coding");}
                else{p=Path.of(database).toAbsolutePath().normalize().getParent().resolve("coding");}
            }else{p=Path.of(configured);}
            p=p.toAbsolutePath().normalize();Files.createDirectories(p);return p.toRealPath();
        }
        catch (Exception e) { throw new IllegalStateException("无法创建 Coding 数据目录",e); }
    }
    public Path task(String id) {
        if (id == null || !id.matches("[a-f0-9-]{36}")) { throw new IllegalArgumentException("非法任务 ID"); }
        return root().resolve("runs").resolve(id);
    }
    public Path checkout(String id) { return task(id).resolve("checkout"); }
    public Path registerPath(String input) {
        try {
            Path source=Path.of(input).toRealPath();
            if (!Files.isDirectory(source) || source.startsWith(root())) { throw new IllegalArgumentException("请选择 Coding 数据目录以外的 Git 仓库"); }
            String top=git(source,"rev-parse","--show-toplevel").strip();
            if (!Path.of(top).toRealPath().equals(source)) { throw new IllegalArgumentException("请选择 Git 仓库根目录"); }
            git(source,"rev-parse","--verify","HEAD");
            return source;
        } catch (Exception e) { throw new IllegalArgumentException("仓库不可用："+e.getMessage(),e); }
    }
    public void prepare(String id,Repository repo) {
        try {
            Path task=task(id), work=checkout(id), baseline=task.resolve("baseline");
            if (Files.exists(task.resolve("baseline.json"))) { if (!Files.isDirectory(work)) { throw new IllegalStateException("任务文件已丢失"); } return; }
            Files.createDirectories(work); Files.createDirectories(baseline);
            Path source=registerPath(repo.path());
            String paths=git(source,"ls-files","--cached","--others","--exclude-standard","-z");
            TreeMap<String,FileStamp> manifest=new TreeMap<>(); long total=0;
            for (String name:paths.split("\u0000")) {
                if (name.isBlank() || excluded(name)) { continue; }
                Path from=resolve(source,name,false);
                if (!Files.isRegularFile(from,LinkOption.NOFOLLOW_LINKS)) { continue; }
                long size=Files.size(from); total+=size;
                if (manifest.size()>=props.getMaxFiles() || total>props.getMaxWorkspaceBytes()) { throw new IllegalArgumentException("仓库快照超过文件数/体积上限"); }
                byte[] bytes=Files.readAllBytes(from);
                writeFile(work,name,bytes); writeFile(baseline,name,bytes);
                boolean executable=Files.isExecutable(from);
                work.resolve(name).toFile().setExecutable(executable,false); baseline.resolve(name).toFile().setExecutable(executable,false);
                manifest.put(name,new FileStamp(hash(bytes),size,executable));
            }
            git(work,"init","--quiet","--separate-git-dir",task.resolve("git").toString());
            // Highest-priority attributes preserve byte-level snapshots and disable repository filters.
            Files.createDirectories(task.resolve("git/info"));
            Files.writeString(task.resolve("git/info/attributes"),"* -text -filter -working-tree-encoding\n",StandardCharsets.UTF_8);
            if(!manifest.isEmpty()){stagePaths(id,manifest.keySet(),"baseline-paths.txt");}
            git(work,"-c","user.name=Minis Coding","-c","user.email=coding@localhost","commit","--quiet","--allow-empty","--no-verify","-m","Coding baseline");
            json.writeValue(task.resolve("source.json").toFile(),Map.of("head",git(source,"rev-parse","HEAD").strip(),"path",source.toString()));
            json.writeValue(task.resolve("baseline.json").toFile(),manifest);
        } catch (Exception e) { throw new IllegalStateException("建立隔离快照失败："+e.getMessage(),e); }
    }
    /** Initialize a task from a validated workspace archive; source remains in Docker/SSH. */
    public void prepareImported(String id,Map<String,byte[]> content,Set<String> executable) throws Exception {
        if(Files.isRegularFile(task(id).resolve("baseline.json"))){return;}
        Path work=checkout(id), baseline=task(id).resolve("baseline");
        Files.createDirectories(work);Files.createDirectories(baseline);
        TreeMap<String,FileStamp> stamps=new TreeMap<>();
        for(var entry:content.entrySet()){
            writeFile(work,entry.getKey(),entry.getValue());writeFile(baseline,entry.getKey(),entry.getValue());
            boolean mode=executable.contains(entry.getKey());
            work.resolve(entry.getKey()).toFile().setExecutable(mode,false);baseline.resolve(entry.getKey()).toFile().setExecutable(mode,false);
            stamps.put(entry.getKey(),new FileStamp(hash(entry.getValue()),entry.getValue().length,mode));
        }
        git(work,"init","--quiet","--separate-git-dir",task(id).resolve("git").toString());
        Files.createDirectories(task(id).resolve("git/info"));Files.writeString(task(id).resolve("git/info/attributes"),"* -text -filter -working-tree-encoding\n");
        if(!stamps.isEmpty()){stagePaths(id,stamps.keySet(),"baseline-paths.txt");}
        git(work,"-c","user.name=Minis Coding","-c","user.email=coding@localhost","commit","--quiet","--allow-empty","--no-verify","-m","Workspace baseline");
        json.writeValue(task(id).resolve("workspace-modes.json").toFile(),executable);
        json.writeValue(task(id).resolve("baseline.json").toFile(),stamps);
    }
    public void replaceImported(String id,Map<String,byte[]> content,Set<String> executable) throws Exception {
        for(String previous:manifest(id).keySet()){if(!content.containsKey(previous)){writeFile(checkout(id),previous,null);}}
        for(var entry:content.entrySet()){
            writeFile(checkout(id),entry.getKey(),entry.getValue());checkout(id).resolve(entry.getKey()).toFile().setExecutable(executable.contains(entry.getKey()),false);
        }
        json.writeValue(task(id).resolve("workspace-modes.json").toFile(),executable);
    }
    public Path resolve(Path root,String relative,boolean newFile) throws Exception {
        if (relative==null || relative.isBlank() || relative.indexOf('\0')>=0 || relative.indexOf('\n')>=0 || relative.indexOf('\\')>=0 || relative.indexOf(':')>=0) { throw new IllegalArgumentException("请使用仓库内相对路径，分隔符为 /"); }
        Path rel=Path.of(relative);
        for(Path segment:rel){if(segment.toString().equals("..")){throw new IllegalArgumentException("路径不允许包含 ..");}}
        if (rel.isAbsolute() || rel.normalize().startsWith("..") || excluded(relative)) { throw new IllegalArgumentException("路径不在允许的代码范围内"); }
        Path base=root.toRealPath(), result=base.resolve(rel).normalize();
        if (!result.startsWith(base) || result.equals(base)) { throw new IllegalArgumentException("路径越界"); }
        Path cursor=base;
        for (Path segment:base.relativize(result)) {
            cursor=cursor.resolve(segment);
            if (Files.exists(cursor,LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(cursor) || !cursor.toRealPath().equals(cursor.toAbsolutePath().normalize())) { throw new IllegalArgumentException("不允许通过链接访问文件"); }
                if (Files.isRegularFile(cursor,LinkOption.NOFOLLOW_LINKS)) {
                    try { if (((Number)Files.getAttribute(cursor,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).longValue()>1) { throw new IllegalArgumentException("不允许访问硬链接文件"); } }
                    catch (UnsupportedOperationException ignored) { /* Windows has no unix attribute view. */ }
                }
            }
        }
        if (!newFile && !Files.exists(result,LinkOption.NOFOLLOW_LINKS)) { throw new IllegalArgumentException("文件不存在："+relative); }
        return result;
    }
    public Map<String,FileStamp> manifest(String id) {
        try {
            TreeMap<String,FileStamp> files=new TreeMap<>(); long total=0;
            Path work=checkout(id);
            Set<String> remoteModes=null;
            Path modeFile=task(id).resolve("workspace-modes.json");
            if(Files.isRegularFile(modeFile)){remoteModes=new HashSet<>();for(JsonNode value:json.readTree(modeFile.toFile())){remoteModes.add(value.asText());}}
            {
                for (Path p:scanFiles(work)) {
                    String rel=work.relativize(p).toString().replace('\\','/');
                    if (rel.isEmpty() || excluded(rel) || Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)) { continue; }
                    p=resolve(work,rel,false);
                    if (!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)) { throw new IllegalArgumentException("非普通文件："+rel); }
                    total+=Files.size(p);
                    if (files.size()>=props.getMaxFiles() || total>props.getMaxWorkspaceBytes()) { throw new IllegalArgumentException("任务文件超过上限"); }
                    files.put(rel,new FileStamp(hash(Files.readAllBytes(p)),Files.size(p),remoteModes==null?Files.isExecutable(p):remoteModes.contains(rel)));
                }
            }
            return files;
        } catch (Exception e) { throw new IllegalStateException("无法核验任务文件："+e.getMessage(),e); }
    }
    private List<Path> scanFiles(Path work) throws Exception {
        List<Path> result=new ArrayList<>();
        Files.walkFileTree(work,new SimpleFileVisitor<>(){
            private int directories;
            @Override public FileVisitResult preVisitDirectory(Path dir,java.nio.file.attribute.BasicFileAttributes attrs) {
                String name=work.relativize(dir).toString().replace('\\','/');
                if(!name.isEmpty() && excluded(name)){return FileVisitResult.SKIP_SUBTREE;}
                if(++directories>props.getMaxFiles()){throw new IllegalArgumentException("目录数量超过上限");}
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,java.nio.file.attribute.BasicFileAttributes attrs) {
                if(!excluded(work.relativize(file).toString().replace('\\','/'))){result.add(file);if(result.size()>props.getMaxFiles()){throw new IllegalArgumentException("文件数超过上限");}}
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }
    public Map<String,FileStamp> baseline(String id) {
        try { return json.readValue(task(id).resolve("baseline.json").toFile(),new TypeReference<TreeMap<String,FileStamp>>() {}); }
        catch (Exception e) { throw new IllegalStateException("任务基准快照不可用",e); }
    }
    public String treeHash(String id) { try { return hash(json.writeValueAsBytes(manifest(id))); } catch (Exception e) { throw new IllegalStateException(e); } }
    public Diff diff(String id) {
        synchronized(gitLocks.computeIfAbsent(id,ignored->new Object())){return computeDiff(id);}
    }
    public Object mutex(String id){return gitLocks.computeIfAbsent(id,ignored->new Object());}
    private Diff computeDiff(String id) {
        try {
            var before=baseline(id); var after=manifest(id); var names=new TreeSet<>(before.keySet()); names.addAll(after.keySet());
            var changes=new ArrayList<Change>();
            for (String path:names) {
                FileStamp a=before.get(path), b=after.get(path);
                if (Objects.equals(a,b)) { continue; }
                byte[] bytes=Files.readAllBytes(resolve(b==null?task(id).resolve("baseline"):checkout(id),path,false));
                changes.add(new Change(path,a==null?"added":b==null?"deleted":"modified",a==null?"MISSING":a.hash(),b==null?"MISSING":b.hash(),b==null?0:b.size(),binary(bytes),b!=null && b.executable()));
            }
            String artifact=treeHash(id);
            // Explicit literal paths include newly ignored files but never stage generated dependencies.
            git(checkout(id),"read-tree","HEAD");
            if(!names.isEmpty()){
                stagePaths(id,names,"diff-paths.txt");
            }
            String patch=git(checkout(id),"diff","--cached","--no-ext-diff","--no-textconv","--binary","HEAD","--");
            boolean clipped=patch.length()>150_000;
            Diff diff=new Diff(artifact,changes,clipped?patch.substring(0,150_000):patch,clipped);
            json.writeValue(task(id).resolve("artifact.json").toFile(),diff);
            return diff;
        } catch (Exception e) { throw new IllegalStateException("无法生成变更产物："+e.getMessage(),e); }
    }
    public Object read(String id,String path,int start,int count) throws Exception {
        Path p=resolve(checkout(id),path,false);
        if (Files.size(p)>props.getMaxFileBytes()) { throw new IllegalArgumentException("文件超过读取上限"); }
        byte[] bytes=Files.readAllBytes(p);
        if (binary(bytes)) { return Map.of("path",path,"binary",true,"size",bytes.length,"hash",hash(bytes)); }
        String[] lines=new String(bytes,StandardCharsets.UTF_8).split("\n",-1);
        int from=Math.min(lines.length,Math.max(0,start-1)), to=Math.min(lines.length,from+Math.max(1,Math.min(500,count)));
        return Map.of("path",path,"hash",hash(bytes),"startLine",from+1,"totalLines",lines.length,"content",String.join("\n",Arrays.copyOfRange(lines,from,to)));
    }
    public List<String> list(String id,String directory) throws Exception {
        Path work=checkout(id), base=directory==null||directory.isBlank()||directory.equals(".")?work:resolve(work,directory,false);
        if (!Files.isDirectory(base,LinkOption.NOFOLLOW_LINKS)) { throw new IllegalArgumentException("不是目录"); }
        try(var stream=Files.walk(base,4)) { return stream.filter(p->!p.equals(base)).map(p->work.relativize(p).toString().replace('\\','/')).filter(p->!excluded(p)).sorted().limit(1000).toList(); }
    }
    public List<Map<String,Object>> search(String id,String query,String glob) throws Exception {
        if (query==null || query.isBlank() || query.length()>500) { throw new IllegalArgumentException("搜索文本不能为空且不得超过 500 字符"); }
        PathMatcher matcher=FileSystems.getDefault().getPathMatcher("glob:"+(glob==null||glob.isBlank()?"**":glob));
        var result=new ArrayList<Map<String,Object>>();
        for (String name:manifest(id).keySet()) {
            if (!matcher.matches(Path.of(name)) && !matcher.matches(Path.of(name).getFileName())) { continue; }
            Path p=resolve(checkout(id),name,false); if(Files.size(p)>props.getMaxFileBytes()) { continue; }
            byte[] bytes=Files.readAllBytes(p); if(binary(bytes)) { continue; }
            String[] lines=new String(bytes,StandardCharsets.UTF_8).split("\n");
            for(int i=0;i<lines.length;i++) { if(lines[i].contains(query)) { result.add(Map.of("path",name,"line",i+1,"text",lines[i].substring(0,Math.min(1000,lines[i].length())))); if(result.size()>=100) { return result; } } }
        }
        return result;
    }
    public void patch(String id,JsonNode patches) throws Exception {
        if(!patches.isArray() || patches.isEmpty() || patches.size()>16) { throw new IllegalArgumentException("patches 必须包含 1–16 个变更"); }
        Map<String,byte[]> original=new LinkedHashMap<>(), updated=new LinkedHashMap<>();
        Set<Path> targets=new HashSet<>();
        for(JsonNode patch:patches) {
            String path=patch.path("path").asText(); Path p=resolve(checkout(id),path,true);
            if(!targets.add(p)){throw new IllegalArgumentException("同一批重复访问同一文件");}
            if(original.containsKey(path)) { throw new IllegalArgumentException("同一批不得重复修改一个文件"); }
            byte[] old=Files.exists(p)?Files.readAllBytes(p):null;
            if(old!=null && (old.length>props.getMaxFileBytes() || binary(old))) { throw new IllegalArgumentException("补丁仅支持上限内的 UTF-8 文本"); }
            String expected=patch.path("expectedHash").asText();
            if(!expected.equals(old==null?"MISSING":hash(old))) { throw new IllegalArgumentException("文件已变化，请重新读取："+path); }
            original.put(path,old);
            if(patch.path("delete").asBoolean(false)) { if(old==null) { throw new IllegalArgumentException("文件不存在"); } updated.put(path,null); continue; }
            String search=patch.path("oldText").asText(""), replace=patch.path("newText").asText("");
            String text=old==null?"":new String(old,StandardCharsets.UTF_8), next;
            if(old==null) { if(!search.isEmpty()) { throw new IllegalArgumentException("新文件 oldText 必须为空"); } next=replace; }
            else { int at=text.indexOf(search); if(search.isEmpty() || at<0 || text.indexOf(search,at+search.length())>=0) { throw new IllegalArgumentException("oldText 必须唯一匹配，包含足够上下文："+path); } next=text.substring(0,at)+replace+text.substring(at+search.length()); }
            byte[] bytes=next.getBytes(StandardCharsets.UTF_8); if(bytes.length>props.getMaxFileBytes()) { throw new IllegalArgumentException("修改后文件超限"); } updated.put(path,bytes);
        }
        try { for(var entry:updated.entrySet()) { writeFile(checkout(id),entry.getKey(),entry.getValue()); } }
        catch(Exception error) { for(var entry:original.entrySet()) { writeFile(checkout(id),entry.getKey(),entry.getValue()); } throw error; }
    }
    public synchronized Diff accept(String id,Repository repo,String expected) throws Exception {
        Diff diff=diff(id);
        if(!Objects.equals(diff.artifactHash(),expected)) { throw new IllegalArgumentException("产物已变化，请重新审查"); }
        Path source=registerPath(repo.path());
        JsonNode sourceInfo=json.readTree(task(id).resolve("source.json").toFile());
        if(!sourceInfo.path("head").asText().equals(git(source,"rev-parse","HEAD").strip())) { throw new IllegalArgumentException("原仓库 HEAD 已变化，拒绝覆盖"); }
        for(var entry:baseline(id).entrySet()) {
            Path p=resolve(source,entry.getKey(),true);
            if(!Files.exists(p) || !hash(Files.readAllBytes(p)).equals(entry.getValue().hash())) { throw new IllegalArgumentException("原仓库已变化："+entry.getKey()); }
        }
        for(Change change:diff.changes()) { Path p=resolve(source,change.path(),true); if(change.beforeHash().equals("MISSING") && Files.exists(p)) { throw new IllegalArgumentException("原仓库出现同名文件："+change.path()); } }
        json.writeValue(task(id).resolve("accept-journal.json").toFile(),diff);
        var applied=new ArrayList<Change>();
        try {
            for(Change change:diff.changes()) {
                byte[] bytes=change.afterHash().equals("MISSING")?null:Files.readAllBytes(resolve(checkout(id),change.path(),false));
                writeFile(source,change.path(),bytes);
                if(bytes!=null){resolve(source,change.path(),false).toFile().setExecutable(change.executable(),false);}
                applied.add(change);
            }
        } catch(Exception error) {
            for(Change change:applied.reversed()) { byte[] bytes=change.beforeHash().equals("MISSING")?null:Files.readAllBytes(resolve(task(id).resolve("baseline"),change.path(),false)); writeFile(source,change.path(),bytes); if(bytes!=null){source.resolve(change.path()).toFile().setExecutable(baseline(id).get(change.path()).executable(),false);} }
            throw error;
        }
        Files.move(task(id).resolve("accept-journal.json"),task(id).resolve("accepted.json"),StandardCopyOption.REPLACE_EXISTING);
        return diff;
    }
    private void writeFile(Path root,String path,byte[] bytes) throws Exception {
        Path target=resolve(root,path,true);
        if(bytes==null) { Files.deleteIfExists(target); return; }
        Files.createDirectories(target.getParent()); target=resolve(root,path,true);
        java.util.Set<java.nio.file.attribute.PosixFilePermission> permissions=null;
        try{if(Files.exists(target)){permissions=Files.getPosixFilePermissions(target);}}catch(UnsupportedOperationException ignored){}
        Path tmp=Files.createTempFile(target.getParent(),".coding-",".tmp");
        if(permissions!=null){Files.setPosixFilePermissions(tmp,permissions);}
        try { Files.write(tmp,bytes); try{ Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }catch(AtomicMoveNotSupportedException e){ Files.move(tmp,target,StandardCopyOption.REPLACE_EXISTING); } }
        finally { Files.deleteIfExists(tmp); }
    }
    private void stagePaths(String id,Collection<String> names,String file) throws Exception{
        Path paths=task(id).resolve(file);StringBuilder spec=new StringBuilder();
        for(String name:names){spec.append(name).append('\0');}
        Files.writeString(paths,spec,StandardCharsets.UTF_8);
        git(checkout(id),"add","--all","--force","--pathspec-from-file="+paths,"--pathspec-file-nul");
    }
    public String git(Path root,String... args) {
        var command=new ArrayList<>(List.of("git","-c","core.hooksPath=/dev/null","-c","core.fsmonitor=false","-c","core.autocrlf=false","-c","core.safecrlf=false")); command.addAll(List.of(args));
        Path privateGit=root.getParent().resolve("git");
        if(root.getFileName().toString().equals("checkout") && root.startsWith(root()) && Files.isDirectory(privateGit)) {
            command=new ArrayList<>(List.of("git","--git-dir="+privateGit,"--work-tree="+root,"-c","core.hooksPath=/dev/null","-c","core.fsmonitor=false","-c","core.autocrlf=false","-c","core.safecrlf=false")); command.addAll(List.of(args));
        }
        var out=HostCommands.run(command,root,20,8*1024*1024);
        if(out.exitCode()!=0) { throw new IllegalArgumentException("Git："+out.stderr()); } return out.stdout();
    }
    public static boolean binary(byte[] bytes) {
        for(byte b:bytes){ if(b==0){ return true; } }
        try{StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes));return false;}
        catch(java.nio.charset.CharacterCodingException error){return true;}
    }
    public static String hash(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch(Exception e){ throw new IllegalStateException(e); } }
    public static boolean excluded(String path) {
        String value=path.replace('\\','/');
        for(String part:value.split("/")) {
            String lower=part.toLowerCase(Locale.ROOT);
            if(Set.of(".git","node_modules","target","dist","build",".aws",".ssh",".codex",".gradle",".idea",".mvn-cache",".minis-coding").contains(lower)
                    || lower.startsWith(".coding-")
                    || lower.equals(".env") || lower.startsWith(".env.") && !lower.endsWith("example")
                    || lower.endsWith(".pem") || lower.endsWith(".key") || lower.endsWith(".p12") || lower.endsWith(".db") || lower.endsWith(".db-wal") || lower.endsWith(".db-shm") || lower.endsWith(".vscdb")) { return true; }
        }
        return value.startsWith("data/coding/") || value.startsWith("data/logs/") || value.startsWith("data/backups/");
    }
}
