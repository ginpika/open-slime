package cc.ginpika.minis.ai.computer.terminal.exec;

import cc.ginpika.minis.ai.computer.terminal.ExecTarget;
import cc.ginpika.minis.ai.computer.terminal.store.TerminalStore;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.KeyType;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.userauth.UserAuthException;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import net.schmizz.sshj.userauth.password.PasswordFinder;
import net.schmizz.sshj.userauth.password.PasswordUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * SSH 实现：走 sshj 连接远程服务器执行命令（密码 / 私钥认证）。
 *
 * <p><b>会话复用</b>：SSH 握手（TCP + 密钥交换 + 认证）是几百毫秒到一秒的开销，
 * 每条命令都新建连接会让「连续看几个文件」变得难以忍受，故按目标缓存连接、断线自动重连。
 *
 * <p><b>每目标串行</b>：同一目标上的命令排队执行。SSH 本身允许多通道并存，
 * 但「先 cd 到某处、再操作那里的文件」这类隐含顺序一旦被打乱就会得到难以解释的结果——
 * 与工具层「多数工具有顺序语义」的默认判断一致（只有明确声明可并行的工具才并发）。
 *
 * <p><b>host key</b>：默认是「首次信任」（TOFU，同 OpenSSH 的 {@code accept-new}）——
 * 未知主机把它的指纹记进 {@code ~/.ssh/known_hosts} 后放行，之后逐次校验，指纹变化则拒绝；
 * {@code accept-all} 完全不校验，只在明确知道自己在连什么时才该用。
 */
@Component
public class SshExecTarget implements ExecTarget {

    public static final String KIND = "ssh";

    /** 认证方式：密码 */
    public static final String AUTH_PASSWORD = "password";
    /** 认证方式：私钥（内容或路径） */
    public static final String AUTH_KEY = "key";

    /**
     * host key 策略：首次信任（默认）。未知主机自动记入 {@code ~/.ssh/known_hosts} 并放行，
     * 已记录的主机逐次校验、指纹变化即拒绝——既不必手工造 known_hosts，又不放弃中间人防护。
     */
    public static final String HOST_KEY_KNOWN = "known_hosts";
    /** host key 策略：不校验（有中间人风险，仅在自建内网等可控场景使用） */
    public static final String HOST_KEY_ACCEPT_ALL = "accept-all";

    private static final int DEFAULT_PORT = 22;
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    private static final Logger log = LoggerFactory.getLogger(SshExecTarget.class);

    /** 目标 id → 复用的连接 */
    private final ConcurrentHashMap<String, SSHClient> clients = new ConcurrentHashMap<>();
    /** 目标 id → 串行锁（保住同目标上的命令顺序） */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public ExecOutcome run(TerminalStore.TargetRow target, String command, ExecOptions options) {
        String wrapped = ExecTarget.wrap(options.workdir(), command, options.timeoutMs());
        Object lock = locks.computeIfAbsent(target.id(), k -> new Object());
        synchronized (lock) {
            long started = System.currentTimeMillis();
            try {
                return exec(target, wrapped, options, started);
            } catch (UserAuthException e) {
                /* 认证失败不是「连接失效」：重试只会白多一次失败（可能触发远端的失败计数），
                   且报错该指向凭证而不是命令——所以这一支不重试、单独报 */
                invalidate(target.id());
                throw new IllegalStateException("SSH 认证失败（" + user(target) + "@"
                        + target.sshHost() + "）：请检查用户名与密码 / 私钥；" + e.getMessage(), e);
            } catch (IOException e) {
                /* 连接可能已失效（远端重启、网络抖动）：丢掉它，下一次调用重连后再试一次 */
                invalidate(target.id());
                try {
                    return exec(target, wrapped, options, started);
                } catch (IOException retry) {
                    throw new IllegalStateException("SSH 执行失败（" + target.sshUser() + "@"
                            + target.sshHost() + "）：" + retry.getMessage(), retry);
                }
            }
        }
    }

    @Override
    public void probe(TerminalStore.TargetRow target) {
        Object lock = locks.computeIfAbsent(target.id(), k -> new Object());
        synchronized (lock) {
            try {
                SSHClient ssh = client(target);
                try (Session session = ssh.startSession()) {
                    Session.Command cmd = session.exec("true");
                    cmd.join(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    Integer status = cmd.getExitStatus();
                    if (status == null || status != 0) {
                        throw new IllegalStateException("已登录但无法执行命令（exit=" + status + "）");
                    }
                }
            } catch (IOException e) {
                invalidate(target.id());
                throw new IllegalStateException("SSH 连接失败（" + target.sshUser() + "@"
                        + target.sshHost() + "）：" + e.getMessage(), e);
            }
        }
    }

    @Override
    public Shell openShell(TerminalStore.TargetRow target, String command, int cols, int rows) throws IOException {
        // Dedicated connection: closing a terminal must never cancel an Agent's command channel.
        SSHClient ssh = connect(target);
        try {
            Session session = ssh.startSession();
            session.allocatePTY("xterm-256color", cols, rows, 0, 0, java.util.Map.of());
            Session.Command channel = session.exec("exec sh -lc " + ExecTarget.quote(command));
            return new SshShell(ssh, session, channel);
        } catch (Exception error) {
            try { ssh.close(); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            if (error instanceof IOException io) throw io;
            throw new IOException("SSH 终端启动失败", error);
        }
    }

    /** Explicit constructor keeps the shell independent of anonymous-class capture ordering. */
    private static final class SshShell implements Shell {
        private final SSHClient ssh;
        private final Session session;
        private final Session.Command channel;
        private final Session.Shell window;

        SshShell(SSHClient ssh, Session session, Session.Command channel) {
            this.ssh = ssh;
            this.session = session;
            this.channel = channel;
            // SSHJ's SessionChannel implements the PTY resize API for exec requests too.
            this.window = (Session.Shell) session;
        }

        public InputStream output() { return channel.getInputStream(); }
        public java.io.OutputStream input() { return channel.getOutputStream(); }
        public void resize(int width, int height) throws IOException { window.changeWindowDimensions(width, height, 0, 0); }
        public void close() throws IOException { try { session.close(); } finally { ssh.close(); } }
    }

    /** 关掉全部复用连接（应用停止时） */
    @PreDestroy
    void shutdown() {
        for (SSHClient client : clients.values()) {
            close(client);
        }
        clients.clear();
    }

    // ---------- 一次执行 ----------

    private ExecOutcome exec(TerminalStore.TargetRow target, String wrapped, ExecOptions options,
                             long started) throws IOException {
        SSHClient ssh = client(target);
        try (Session session = ssh.startSession()) {
            Session.Command cmd = session.exec(wrapped);
            StreamPump out = new StreamPump(cmd.getInputStream(), options.maxOutputChars());
            StreamPump err = new StreamPump(cmd.getErrorStream(), options.maxOutputChars());
            Thread pumpOut = Thread.ofVirtual().start(out);
            Thread pumpErr = Thread.ofVirtual().start(err);

            /* join(timeout) 只等到点为止（返回值是 void），故用「退出码是否已到达」判定有没有超时 */
            cmd.join(options.timeoutMs(), TimeUnit.MILLISECONDS);
            Integer status = cmd.getExitStatus();
            /* 关通道即撤掉这个 exec 请求：sshd 会终结它拉起的进程组，
               远端那条 timeout 是第二道保险（见 ExecTarget#wrap） */
            cmd.close();
            session.close();
            join(pumpOut);
            join(pumpErr);

            long elapsed = System.currentTimeMillis() - started;
            return new ExecOutcome(status == null ? NO_EXIT_CODE : status, out.text(), err.text(),
                    elapsed, out.truncated() || err.truncated(), status == null);
        }
    }

    private static void join(Thread pump) {
        try {
            pump.join(2_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- 连接管理 ----------

    private SSHClient client(TerminalStore.TargetRow target) throws IOException {
        SSHClient existing = clients.get(target.id());
        if (existing != null && existing.isConnected() && existing.isAuthenticated()) {
            return existing;
        }
        invalidate(target.id());
        SSHClient fresh = connect(target);
        clients.put(target.id(), fresh);
        log.info("已建立 SSH 连接：{}@{}:{}", target.sshUser(), target.sshHost(), port(target));
        return fresh;
    }

    private SSHClient connect(TerminalStore.TargetRow target) throws IOException {
        if (blank(target.sshHost())) {
            throw new IllegalStateException("SSH 目标缺少 ssh_host");
        }
        SSHClient ssh = new SSHClient();
        ssh.setConnectTimeout(CONNECT_TIMEOUT_MS);
        if (HOST_KEY_ACCEPT_ALL.equalsIgnoreCase(target.hostKeyCheck())) {
            ssh.addHostKeyVerifier(new PromiscuousVerifier());
        } else {
            ssh.addHostKeyVerifier(knownHosts());
        }
        try {
            ssh.connect(target.sshHost(), port(target));
            if (AUTH_KEY.equalsIgnoreCase(target.sshAuth())) {
                ssh.authPublickey(user(target), keyProvider(ssh, target));
            } else {
                ssh.authPassword(user(target), target.sshPassword() == null ? "" : target.sshPassword());
            }
            return ssh;
        } catch (IOException | RuntimeException error) {
            close(ssh);
            throw error;
        }
    }

    /**
     * 取「首次信任」的 known_hosts 校验器（目录与文件按需创建）。
     *
     * <p>没有用现成的 {@code ssh.loadKnownHosts()}：它只在 {@code ~/.ssh/known_hosts}（或
     * {@code known_hosts2}）里找现成的文件，两个都不在就抛 {@code Could not load known_hosts}——
     * 于是「第一次连一台新机器」这个最正常的场景反而要先手工造文件，容器里连 {@code .ssh}
     * 目录都没有，更是必然失败。改成 TOFU 后与 OpenSSH 的 {@code accept-new} 同义。
     */
    private static OpenSSHKnownHosts knownHosts() throws IOException {
        File dir = new File(System.getProperty("user.home"), ".ssh");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建 SSH 配置目录：" + dir + "（请检查该路径是否可写）");
        }
        return new TrustOnFirstUseKnownHosts(new File(dir, "known_hosts"));
    }

    /**
     * 带「首次信任」的 known_hosts（TOFU）。
     *
     * <p>只覆写「未知主机」这一支：把它的 host key 追加进文件并放行，之后每次连接都逐字校验。
     * <b>刻意不动</b>「指纹变化」那一支——已记录的主机换了 key 正是中间人的信号，照旧拒绝
     * （VM 重建、系统重装导致指纹合法变更时，需先把文件里那一行删掉）。
     */
    private static final class TrustOnFirstUseKnownHosts extends OpenSSHKnownHosts {

        /** 写进 known_hosts 的注释：日后在文件里翻到时能认出这一行是谁加的 */
        private static final String COMMENT = "added-by-minis-terminal";

        TrustOnFirstUseKnownHosts(File file) throws IOException {
            /* 这里要的是 sshj 自己的 LoggerFactory（构造参数类型），不是本类的 slf4j.LoggerFactory */
            super(file, net.schmizz.sshj.common.LoggerFactory.DEFAULT);
        }

        @Override
        protected boolean hostKeyUnverifiableAction(String hostname, PublicKey key) {
            try {
                /* 这里的 hostname 已按 known_hosts 规则归一过（非 22 端口是 [host]:port），可直接落盘 */
                HostEntry entry = new HostEntry(null, hostname, KeyType.fromKey(key), key, COMMENT);
                write(entry);
                entries().add(entry);
                log.info("首次连接 {}：已把它的 host key 记入 {}", hostname, getFile());
                return true;
            } catch (IOException e) {
                /* HostEntry 构造抛的 SSHException 也是 IOException 的子类，一并收在这里 */
                log.warn("无法把 {} 的 host key 写入 {}（{}）：本次连接按校验失败处理，"
                        + "可把该目标的 Host Key 校验改为 accept-all", hostname, getFile(),
                        e.getMessage());
                return false;
            }
        }
    }

    /**
     * 私钥取值：内容里带 "PRIVATE KEY" 就当 PEM 正文，否则当文件路径
     * （两种在设置页写同一个框——粘私钥比填路径更常见，路径留给密钥本就落在服务器上的场景）。
     */
    private static KeyProvider keyProvider(SSHClient ssh, TerminalStore.TargetRow target)
            throws IOException {
        String key = blank(target.sshPrivateKey()) ? "" : target.sshPrivateKey().strip();
        if (key.isEmpty()) {
            throw new IllegalStateException("SSH 目标选择了私钥认证，但未提供私钥");
        }
        String passphrase = blank(target.sshPassphrase()) ? null : target.sshPassphrase();
        if (key.contains("PRIVATE KEY")) {
            PasswordFinder finder = passphrase == null
                    ? null : PasswordUtils.createOneOff(passphrase.toCharArray());
            return ssh.loadKeys(key, null, finder);
        }
        return passphrase == null ? ssh.loadKeys(key) : ssh.loadKeys(key, passphrase);
    }

    private void invalidate(String targetId) {
        close(clients.remove(targetId));
    }

    private static void close(SSHClient client) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (IOException e) {
            log.debug("SSH 连接关闭失败（忽略）：{}", e.getMessage());
        }
    }

    private static int port(TerminalStore.TargetRow target) {
        return target.sshPort() == null || target.sshPort() <= 0 ? DEFAULT_PORT : target.sshPort();
    }

    private static String user(TerminalStore.TargetRow target) {
        return blank(target.sshUser()) ? "root" : target.sshUser();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 把通道的输出读进有上限的缓冲：SSH 的窗口是有限的，不边收边读会把远端写阻塞在通道里
     * （于是命令永远跑不完，看起来像卡死）；但输出又必须截断，所以读而不留。
     */
    private static final class StreamPump implements Runnable {

        private final InputStream in;
        private final int cap;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private final AtomicBoolean truncated = new AtomicBoolean();

        StreamPump(InputStream in, int cap) {
            this.in = in;
            this.cap = Math.max(1, cap);
        }

        @Override
        public void run() {
            byte[] chunk = new byte[8192];
            try {
                int n;
                while ((n = in.read(chunk)) > 0) {
                    int room = cap - buf.size();
                    if (room <= 0) {
                        truncated.set(true);
                        continue;
                    }
                    if (n > room) {
                        truncated.set(true);
                    }
                    buf.write(chunk, 0, Math.min(room, n));
                }
            } catch (IOException e) {
                /* 通道被关（超时 / 命令结束）时读会抛：正常收尾，不算失败 */
            }
        }

        String text() {
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }

        boolean truncated() {
            return truncated.get();
        }
    }
}
