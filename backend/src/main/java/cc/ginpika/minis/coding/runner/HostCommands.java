package cc.ginpika.minis.coding.runner;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Only trusted, argument-separated infrastructure commands use this helper. No model shell here. */
public final class HostCommands {
    private HostCommands() {}
    public record Output(int exitCode, String stdout, String stderr) {}
    public static ProcessBuilder builder(List<String> args, Path cwd) {
        var builder = new ProcessBuilder(args);
        if (cwd != null) { builder.directory(cwd.toFile()); }
        Map<String,String> original = Map.copyOf(builder.environment());
        builder.environment().clear();
        for (String name : List.of("PATH","Path","SYSTEMROOT","SystemRoot","WINDIR","TEMP","TMP","PATHEXT","COMSPEC","USERPROFILE")) {
            if (original.containsKey(name)) { builder.environment().put(name, original.get(name)); }
        }
        if(!args.isEmpty() && args.getFirst().equals("docker")){
            for(String name:List.of("HOME","DOCKER_HOST","DOCKER_CONTEXT","DOCKER_CONFIG","DOCKER_CERT_PATH","DOCKER_TLS_VERIFY")){
                if(original.containsKey(name)){builder.environment().put(name,original.get(name));}
            }
        }
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_CONFIG_GLOBAL", System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null");
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("GIT_PAGER", "cat");
        builder.environment().put("GIT_LITERAL_PATHSPECS", "1");
        return builder;
    }
    public static Output run(List<String> args, Path cwd, int seconds, int maxBytes) {
        Process process = null;
        try {
            process = builder(args,cwd).start();
            Process running = process;
            var out = CompletableFuture.supplyAsync(() -> read(running.getInputStream(), maxBytes));
            var err = CompletableFuture.supplyAsync(() -> read(running.getErrorStream(), maxBytes));
            if (!process.waitFor(seconds,TimeUnit.SECONDS)) { stop(process); throw new IllegalStateException("基础命令超时，已请求终止："+args.get(0)); }
            return new Output(process.exitValue(),out.get(3,TimeUnit.SECONDS),err.get(3,TimeUnit.SECONDS));
        } catch (Exception e) {
            if (e instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            if (process != null) { stop(process); }
            throw new IllegalStateException("基础命令失败："+e.getMessage(),e);
        }
    }
    private static String read(InputStream stream, int cap) {
        try (stream; var sink = new java.io.ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192]; int count;
            boolean truncated=false;
            while ((count=stream.read(buffer))!=-1) {
                int keep=Math.min(count,Math.max(0,cap-sink.size()));
                sink.write(buffer,0,keep); truncated |= keep<count;
            }
            if (truncated) { throw new IllegalStateException("基础命令输出超限"); }
            return sink.toString(StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static void stop(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
