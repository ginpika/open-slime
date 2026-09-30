package cc.ginpika.minis.ai.terminal.exec;

import cc.ginpika.minis.ai.terminal.ExecTarget;
import cc.ginpika.minis.ai.terminal.store.TerminalStore;
import cc.ginpika.minis.config.Branding;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * docker 实现：走 Engine API（docker-java），在本机 docker 环境里执行命令。
 *
 * <p><b>常驻会话容器</b>：目标配了 {@code container} 就直接 {@code exec} 进那个既有容器（容器由外部管理）；
 * 只配 {@code image} 时惰性起一个常驻容器（名 {@code <品牌 slug>-term-<目标名>}，跑 {@code sleep infinity}）并复用。
 * 之所以不每次 {@code run --rm}：会话容器让文件、装上的包、改过的配置跨调用保留，
 * 而「先 ls、再 cd、再 cat」这种连续动作正是 Agent 用命令行的常态；一次性容器每次都从零开始。
 *
 * <p><b>超时</b>：{@code awaitCompletion} 到点只让本地不再等，杀不掉容器里的进程，
 * 故命令本身套了 {@code timeout}（见 {@link ExecTarget#wrap}），超时是被真的终止。
 *
 * <p>客户端按 docker host 缓存并复用（每个 client 自带连接池，逐次新建会重新握手）；
 * Windows 上 docker host 留空即可——docker-java 在 Windows 缺省走 npipe。
 */
@Component
public class DockerExecTarget implements ExecTarget {

    public static final String KIND = "docker";

    /** 品牌信息：容器名前缀与示例名都取自 branding.json 的 slug */
    private final Branding branding;

    /** 惰性会话容器名前缀（随品牌名）：与人在 docker 里自己建的容器区分开，便于识别与清理 */
    private final String containerPrefix;

    public DockerExecTarget(Branding branding) {
        this.branding = branding;
        this.containerPrefix = branding.slug() + "-term-";
    }

    /** 会话容器的常驻命令：什么都不做，只是不让容器退出 */
    private static final String[] KEEP_ALIVE = {"sleep", "infinity"};

    /** 连接 daemon 的超时：连不上要快速失败，不能拖住整次对话 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private static final Logger log = LoggerFactory.getLogger(DockerExecTarget.class);

    /** docker host → 复用的客户端（host 为空串表示用环境默认） */
    private final ConcurrentHashMap<String, DockerClient> clients = new ConcurrentHashMap<>();

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public ExecOutcome run(TerminalStore.TargetRow target, String command, ExecOptions options) {
        DockerClient client = client(target.dockerHost());
        String container = ensureContainer(client, target);
        String wrapped = ExecTarget.wrap(options.workdir(), command, options.timeoutMs());

        ExecCreateCmdResponse exec = client.execCreateCmd(container)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withCmd("sh", "-lc", wrapped)
                .exec();

        CappedOutput sink = new CappedOutput(options.maxOutputChars());
        long started = System.currentTimeMillis();
        boolean finished;
        try {
            finished = client.execStartCmd(exec.getId())
                    .exec(sink)
                    .awaitCompletion(options.timeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ExecOutcome(NO_EXIT_CODE, sink.text(), sink.errorText(),
                    System.currentTimeMillis() - started, sink.truncated(), true);
        }
        long elapsed = System.currentTimeMillis() - started;
        if (!finished) {
            return new ExecOutcome(NO_EXIT_CODE, sink.text(), sink.errorText(), elapsed,
                    sink.truncated(), true);
        }
        Integer code = client.inspectExecCmd(exec.getId()).exec().getExitCode();
        return new ExecOutcome(code == null ? NO_EXIT_CODE : code, sink.text(), sink.errorText(),
                elapsed, sink.truncated(), false);
    }

    @Override
    public void probe(TerminalStore.TargetRow target) {
        DockerClient client = client(target.dockerHost());
        client.pingCmd().exec();
        if (!blank(target.container())) {
            InspectContainerResponse ins = client.inspectContainerCmd(target.container()).exec();
            if (!Boolean.TRUE.equals(ins.getState().getRunning())) {
                throw new IllegalStateException("容器存在但未运行：" + target.container()
                        + "（执行时会自动启动，或先自行 docker start）");
            }
        } else {
            requireImage(target);
        }
    }

    @PreDestroy
    void shutdown() {
        for (DockerClient client : clients.values()) {
            try {
                client.close();
            } catch (Exception e) {
                log.debug("docker 客户端关闭失败（忽略）：{}", e.getMessage());
            }
        }
        clients.clear();
    }

    // ---------- 客户端与容器 ----------

    /** 取（或建）某个 docker host 的客户端：留空表示用环境默认（DOCKER_HOST / Windows 上的 npipe） */
    private DockerClient client(String host) {
        String key = host == null ? "" : host.trim();
        return clients.computeIfAbsent(key, k -> {
            DefaultDockerClientConfig.Builder builder =
                    DefaultDockerClientConfig.createDefaultConfigBuilder();
            if (!k.isEmpty()) {
                builder.withDockerHost(k);
            }
            DockerClientConfig config = builder.build();
            DockerHttpClient http = new ApacheDockerHttpClient.Builder()
                    .dockerHost(config.getDockerHost())
                    .sslConfig(config.getSSLConfig())
                    .maxConnections(8)
                    .connectionTimeout(CONNECT_TIMEOUT)
                    /* 不设读超时：命令本身可能合法地跑很久，时长由命令级 timeout 兜底 */
                    .responseTimeout(Duration.ZERO)
                    .build();
            log.info("已建立 docker 客户端：{}", config.getDockerHost());
            return DockerClientImpl.getInstance(config, http);
        });
    }

    /** 拿到可执行命令的容器 id：优先用目标指定的容器，否则确保会话容器存在（必要时惰性创建） */
    private String ensureContainer(DockerClient client, TerminalStore.TargetRow target) {
        String existing = resolveExistingContainer(client, target);
        if (existing != null) {
            return existing;
        }
        requireImage(target);
        String name = containerPrefix + safeName(target.name());
        CreateContainerResponse created = client.createContainerCmd(target.image())
                .withName(name)
                .withCmd(KEEP_ALIVE)
                .withHostConfig(HostConfig.newHostConfig())
                .exec();
        client.startContainerCmd(created.getId()).exec();
        log.info("已创建终端会话容器 {}（镜像 {}，目标 {}）", name, target.image(), target.name());
        return created.getId();
    }

    /**
     * 找出当前可用的容器并保证它在运行；没有则返回 null（不创建）。
     *
     * <p>探测与执行共用它：设置页「测试」不该有副作用，所以探到 null 就只报告「首次执行时创建」。
     */
    private String resolveExistingContainer(DockerClient client, TerminalStore.TargetRow target) {
        if (!blank(target.container())) {
            InspectContainerResponse ins = client.inspectContainerCmd(target.container()).exec();
            if (!Boolean.TRUE.equals(ins.getState().getRunning())) {
                client.startContainerCmd(ins.getId()).exec();
            }
            return ins.getId();
        }
        String name = containerPrefix + safeName(target.name());
        List<Container> found = client.listContainersCmd()
                .withShowAll(true)
                .withNameFilter(List.of(name))
                .exec();
        for (Container c : found) {
            /* nameFilter 是模糊匹配，这里再按容器名精确对一次，免得命中同前缀的别的容器 */
            String[] names = c.getNames();
            if (names == null || !Arrays.asList(names).contains("/" + name)) {
                continue;
            }
            if (!"running".equalsIgnoreCase(c.getState())) {
                client.startContainerCmd(c.getId()).exec();
            }
            return c.getId();
        }
        return null;
    }

    private void requireImage(TerminalStore.TargetRow target) {
        if (blank(target.image())) {
            throw new IllegalStateException("docker 目标既没配 container 也没配 image："
                    + "请指定一个既有容器（如 " + branding.slug() + "-backend），或指定镜像以自动起一个会话容器");
        }
    }

    /** 容器名只允许 [a-zA-Z0-9_.-]，目标名里的其余字符（含中文）一律折成 '-' */
    private static String safeName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_.-]", "-");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 边收边截的输出汇：docker 的回调把每一帧按流类型交过来，这里分别写进 stdout / stderr 两个缓冲。
     *
     * <p>必须自己截断：{@code yes} 这类命令的输出是无界的，原样收进 ByteArrayOutputStream
     * 会把一次执行变成内存事故。超出上限的字节直接丢弃，只置一个 truncated 标记——
     * 模型据此知道「看到的不是全部」，而不是以为命令只输出了这么多。
     */
    private static final class CappedOutput extends ExecStartResultCallback {

        private final int cap;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final ByteArrayOutputStream err = new ByteArrayOutputStream();
        private final AtomicBoolean truncated = new AtomicBoolean();

        CappedOutput(int cap) {
            /* 父类的那两个流不用（onNext 被重写），给空流让它内部状态一致 */
            super(OutputStream.nullOutputStream(), OutputStream.nullOutputStream());
            this.cap = Math.max(1, cap);
        }

        @Override
        public void onNext(Frame frame) {
            byte[] payload = frame.getPayload();
            if (payload == null || payload.length == 0) {
                return;
            }
            ByteArrayOutputStream sink = frame.getStreamType() == StreamType.STDERR ? err : out;
            int room = cap - sink.size();
            if (room <= 0) {
                truncated.set(true);
                return;
            }
            if (payload.length > room) {
                truncated.set(true);
            }
            sink.write(payload, 0, Math.min(room, payload.length));
        }

        byte[] outBytes() {
            return out.toByteArray();
        }

        byte[] errBytes() {
            return err.toByteArray();
        }

        String text() {
            return new String(outBytes(), StandardCharsets.UTF_8);
        }

        String errorText() {
            return new String(errBytes(), StandardCharsets.UTF_8);
        }

        boolean truncated() {
            return truncated.get();
        }
    }
}
