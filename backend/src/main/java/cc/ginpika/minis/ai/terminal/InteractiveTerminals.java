package cc.ginpika.minis.ai.terminal;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Shared PTY lifecycle, buffering and ownership; adapters validate their own scope. */
public abstract class InteractiveTerminals {
    private static final int CAPACITY=1024*1024, CHUNK=65536;
    public record View(String id,String title,boolean running,String error) {}
    public record Output(String data,long nextOffset,boolean reset,boolean running,String error) {}
    public record Chunk(byte[] data,long nextOffset,boolean reset,boolean running,String error) {}
    private static final class Terminal {
        final String id=UUID.randomUUID().toString(),owner,project,title;
        final ExecTarget.Shell shell;
        final Object identity;
        final byte[] buffer=new byte[CAPACITY];
        final AtomicBoolean closed=new AtomicBoolean();
        long end;
        volatile long accessed=System.currentTimeMillis();
        volatile boolean running=true;
        volatile String error;
        Terminal(String owner,String project,String title,ExecTarget.Shell shell,Object identity){this.owner=owner;this.project=project;this.title=title;this.shell=shell;this.identity=identity;}
        View view(){return new View(id,title,running,error);}
        synchronized void append(byte[] bytes,int count){for(int i=0;i<count;i++){buffer[(int)(end++%CAPACITY)]=bytes[i];}notifyAll();}
        synchronized Chunk read(long after){
            if(after<0 || after>end){throw bad("终端游标无效");}
            long start=Math.max(after,Math.max(0,end-CAPACITY)),next=Math.min(end,start+CHUNK);
            byte[] bytes=new byte[(int)(next-start)];
            for(int i=0;i<bytes.length;i++){bytes[i]=buffer[(int)((start+i)%CAPACITY)];}
            return new Chunk(bytes,next,start!=after,running || next<end,error);
        }
        void close(){synchronized(this){running=false;notifyAll();}if(closed.compareAndSet(false,true)){try{shell.close();}catch(Exception ignored){/* Already disconnected. */}}}
    }
    private final Map<String,Terminal> terminals=new ConcurrentHashMap<>();
    private final ScheduledExecutorService reaper=Executors.newSingleThreadScheduledExecutor();
    protected InteractiveTerminals(){
        reaper.scheduleWithFixedDelay(this::expire,1,1,TimeUnit.MINUTES);
    }
    protected abstract Object scope(String owner,String id,boolean enabled);
    protected abstract ExecTarget.Shell openShell(String owner,String id,int cols,int rows) throws IOException;
    public List<View> list(String owner,String project){
        scope(owner,project,false);
        return terminals.values().stream().filter(t->t.owner.equals(owner)&&t.project.equals(project)).sorted(Comparator.comparing(t->t.title)).map(Terminal::view).toList();
    }
    public synchronized View create(String owner,String project,int cols,int rows){
        size(cols,rows);Object identity=scope(owner,project,true);
        if(terminals.size()>=64 || terminals.values().stream().filter(t->t.owner.equals(owner)).count()>=8){throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"终端数量已达上限，请先删除不用的终端（每人最多 8 个）");}
        try{
            var shell=openShell(owner,project,cols,rows);
            Set<String> titles=new HashSet<>();terminals.values().stream().filter(t->t.owner.equals(owner)&&t.project.equals(project)).forEach(t->titles.add(t.title));
            int number=1;while(titles.contains("T"+number)){number++;}String title="T"+number;
            Terminal terminal=new Terminal(owner,project,title,shell,identity);terminals.put(terminal.id,terminal);
            Thread.startVirtualThread(()->pump(terminal));return terminal.view();
        }catch(IOException | IllegalStateException | UnsupportedOperationException error){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"无法创建交互终端："+error.getMessage(),error);}
    }
    public Output read(String owner,String project,String id,long after){var chunk=require(owner,project,id).read(after);return new Output(Base64.getEncoder().encodeToString(chunk.data()),chunk.nextOffset(),chunk.reset(),chunk.running(),chunk.error());}
    /** Wake on PTY bytes or EOF, rather than polling the shell for WebSocket output. */
    public Chunk awaitOutput(String owner,String project,String id,long after) throws InterruptedException {
        Terminal terminal=require(owner,project,id);
        synchronized(terminal){
            if(after==terminal.end && terminal.running){terminal.wait(15000);}
            return terminal.read(after);
        }
    }
    public void checkAccess(String owner,String project,String id,long after){require(owner,project,id).read(after);}
    public void input(String owner,String project,String id,String data){
        Terminal terminal=require(owner,project,id);
        if(data==null || data.length()>65536){throw bad("终端输入过长");}
        if(!terminal.running){throw new ResponseStatusException(HttpStatus.CONFLICT,"终端已退出，请新建终端");}
        try{synchronized(terminal.shell){terminal.shell.input().write(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));terminal.shell.input().flush();}}
        catch(IOException error){terminal.error="终端输入失败："+error.getMessage();terminal.close();throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,terminal.error,error);}
    }
    public void resize(String owner,String project,String id,int cols,int rows){
        size(cols,rows);Terminal terminal=require(owner,project,id);
        if(!terminal.running){return;}
        try{terminal.shell.resize(cols,rows);}catch(IOException error){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"终端缩放失败",error);}
    }
    public void delete(String owner,String project,String id){
        scope(owner,project,false);Terminal terminal=terminals.get(id);if(terminal==null){return;}
        if(!terminal.owner.equals(owner) || !terminal.project.equals(project)){throw new ResponseStatusException(HttpStatus.NOT_FOUND,"终端不存在或不属于当前工作范围");}
        terminals.remove(id,terminal);terminal.close();
    }
    private Terminal require(String owner,String project,String id){
        Terminal terminal=terminals.get(id);
        if(terminal==null || !terminal.owner.equals(owner) || !terminal.project.equals(project)){throw new ResponseStatusException(HttpStatus.NOT_FOUND,"终端不存在或不属于当前工作范围");}
        try {
            if(!Objects.equals(terminal.identity,scope(owner,project,true))){throw new ResponseStatusException(HttpStatus.CONFLICT,"工作空间连接配置发生变化，请新建终端");}
        } catch(RuntimeException error){terminal.close();throw error;}
        terminal.accessed=System.currentTimeMillis();return terminal;
    }
    private void pump(Terminal terminal){
        try{byte[] bytes=new byte[8192];int count;while((count=terminal.shell.output().read(bytes))!=-1){terminal.append(bytes,count);}}
        catch(IOException error){if(!terminal.closed.get()){terminal.error="终端连接已断开："+error.getMessage();}}
        finally{terminal.close();}
    }
    private void expire(){long cutoff=System.currentTimeMillis()-TimeUnit.MINUTES.toMillis(30);terminals.forEach((id,t)->{if(t.accessed<cutoff && terminals.remove(id,t)){t.close();}});}
    private static void size(int cols,int rows){if(cols<2 || cols>500 || rows<1 || rows>200){throw bad("终端尺寸无效");}}
    private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    @PreDestroy public void close(){reaper.shutdownNow();terminals.values().forEach(Terminal::close);terminals.clear();}
}
