package cc.ginpika.minis.coding;

import cc.ginpika.minis.ai.terminal.exec.DockerExecTarget;
import cc.ginpika.minis.ai.terminal.store.TerminalStore.TargetRow;
import cc.ginpika.minis.config.Branding;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in integration test; touches only a fresh, named, automatically removed fixture container. */
@EnabledIfEnvironmentVariable(named="MINIS_TEST_DOCKER",matches="true")
class CodingDockerTerminalTest {
    private static String docker(String... args) throws Exception {
        var command=new java.util.ArrayList<String>();command.add("docker");command.addAll(java.util.List.of(args));
        Process process=new ProcessBuilder(command).redirectErrorStream(true).start();
        if(!process.waitFor(20,TimeUnit.SECONDS)){process.destroyForcibly();throw new IOException("Docker fixture command timed out");}
        String text=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        if(process.exitValue()!=0){throw new IOException(text);}return text.strip();
    }
    @Test void actualPtyPreservesEnvironmentSupportsResizeInterruptAndTerminatesOnClose() throws Exception {
        String name="minis-pty-test-"+UUID.randomUUID();
        String image=System.getenv().getOrDefault("MINIS_TEST_DOCKER_IMAGE","maven:3.9-eclipse-temurin-21");
        var transport=new DockerExecTarget(new Branding());
        try {
            docker("run","--rm","--pull=never","-d","--name",name,"--entrypoint","sh",image,"-c","sleep 300");
            var target=new TargetRow("fixture","fixture","docker",true,"/tmp",30000L,100000,null,null,null,null,name,null,null,null,null,null,null,null,null,null,null,0,0);
            var shell=transport.openShell(target,"cd /tmp && exec bash -i",80,24);
            var bytes=new ByteArrayOutputStream();
            Thread pump=Thread.startVirtualThread(()->{try{byte[] chunk=new byte[4096];int count;while((count=shell.output().read(chunk))!=-1){synchronized(bytes){bytes.write(chunk,0,count);}}}catch(IOException ignored){}});
            try {
                write(shell,"test -t 0 && printf '\\nPTY:%s\\n' yes\r");await(bytes,"PTY:yes");
                write(shell,"printf '\\nSHELL:%s\\n' \"${BASH_VERSION:+bash}\"\r");await(bytes,"SHELL:bash");
                write(shell,"export PTY_FIXTURE=保留变量; cd /; printf '\\nSTATE:%s:%s\\n' \"$PTY_FIXTURE\" \"$PWD\"\r");await(bytes,"STATE:保留变量:/");
                shell.resize(100,30);write(shell,"stty size\r");await(bytes,"30 100");
                write(shell,"sleep 60\r");Thread.sleep(150);write(shell,"\u0003");write(shell,"printf '\\nAFTER:%s\\n' interrupt\r");await(bytes,"AFTER:interrupt");
                write(shell,"sleep 60 & echo $! > /tmp/minis-child.pid; printf '\\nBACKGROUND:%s\\n' started\r");await(bytes,"BACKGROUND:started");
                String child=docker("exec",name,"cat","/tmp/minis-child.pid");assertTrue(child.matches("[0-9]+"));
                String pid=docker("exec",name,"sh","-c","cat /tmp/minis-terminal-*.pid");assertTrue(pid.matches("[0-9]+"));
                shell.close();pump.join(2000);
                assertThrows(IOException.class,()->docker("exec",name,"sh","-c","kill -0 "+pid));
                // A minimal container's PID 1 may leave zombies; these have already terminated.
                docker("exec",name,"sh","-c","test ! -r /proc/"+child+"/status || grep -q '^State:[[:space:]]*Z' /proc/"+child+"/status");
            } finally {shell.close();}
        } finally {try{docker("rm","-f",name);}finally{var shutdown=transport.getClass().getDeclaredMethod("shutdown");shutdown.setAccessible(true);shutdown.invoke(transport);}}
    }
    private static void write(cc.ginpika.minis.ai.terminal.ExecTarget.Shell shell,String data) throws IOException {shell.input().write(data.getBytes(StandardCharsets.UTF_8));shell.input().flush();}
    private static void await(ByteArrayOutputStream bytes,String expected) throws InterruptedException {
        long until=System.currentTimeMillis()+8000;
        while(System.currentTimeMillis()<until){synchronized(bytes){if(bytes.toString(StandardCharsets.UTF_8).contains(expected)){return;}}Thread.sleep(20);}
        synchronized(bytes){fail("Missing "+expected+"; output="+bytes.toString(StandardCharsets.UTF_8));}
    }
}
