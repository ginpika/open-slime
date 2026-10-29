package cc.ginpika.minis.ai.terminal;

import cc.ginpika.minis.ai.terminal.store.TerminalStore;

/**
 * 一台「可执行环境」的执行契约——命令行终端模块两套实现的共同出口。
 *
 * <p>本机 docker 容器（Engine API）与远程服务器（SSH）在协议上天差地别，但对模型必须是同一件事：
 * 一段命令进去，退出码与两路输出出来。差异全部收在各自的实现里（{@code DockerExecTarget} /
 * {@code SshExecTarget}），于是模型侧只有一个工具（{@code run_command}），
 * 换环境只是换 target 参数——与主流 agent 把「在哪执行」作为工具参数而不是工具的写法一致。
 *
 * <p>由 {@link TerminalService} 按 kind 选用：实现类以 {@link #kind()} 注册自己，
 * 与 {@code ai.core.tool.ToolManager} 按 {@code surfaces()} 聚合工具是同一个套路。
 *
 * <p><b>状态语义</b>：每次调用都是一次全新的非交互进程，所以 shell 的工作目录与变量<b>不</b>跨调用保留
 * （远端 exec 通道、docker exec 都是如此）。跨调用留下的是<b>文件系统与环境层面的改动</b>——
 * 写下的文件、装上的包仍在。需要固定起点就把 {@code workdir} 配在目标上，每条命令都会先 cd 进去。
 */
public interface ExecTarget {

    /** 实现种类：与 {@code term_target.kind} 取值一致（docker | ssh） */
    String kind();

    /** 在目标环境里执行一条命令（调用方已完成开关、白/黑名单、超时等护栏判定） */
    ExecOutcome run(TerminalStore.TargetRow target, String command, ExecOptions options);

    /** 连通性探测（设置页「测试」按钮）：能连上即正常返回，连不上抛异常并带上原因 */
    void probe(TerminalStore.TargetRow target);

    /** A persistent PTY owned by an authenticated human session, separate from Agent commands. */
    default Shell openShell(TerminalStore.TargetRow target, String command, int cols, int rows) throws java.io.IOException {
        throw new UnsupportedOperationException("该工作空间不支持交互终端");
    }

    interface Shell extends java.io.Closeable {
        java.io.InputStream output();
        java.io.OutputStream input();
        void resize(int cols, int rows) throws java.io.IOException;
    }

    /** 未拿到退出码时的占位值（超时被杀 / 通道异常）：与真实退出码区分开 */
    int NO_EXIT_CODE = -1;

    /** 单次执行的可选覆盖项（工作目录、超时、输出上限），来自目标的配置 */
    record ExecOptions(String workdir, long timeoutMs, int maxOutputChars) {
    }

    /** 一次执行的结果；{@code timedOut} 与 {@code truncated} 都要如实回给模型，别让它以为看到的是全部 */
    record ExecOutcome(int exitCode, String stdout, String stderr, long durationMs,
                       boolean truncated, boolean timedOut) {

        public boolean ok() {
            return exitCode == 0;
        }
    }

    /**
     * 把一次调用包成远端真正执行的命令行：套一层 {@code timeout} 拿超时兜底，
     * 再套一层 {@code sh -lc} 让工作目录、PATH 与环境变量按登录 shell 的语义生效。
     *
     * <p>{@code timeout} 是独立的第二道保险：传输层的等待超时只能让本地放弃等待，
     * 杀不掉远端进程；套上它，超时是被<b>真的终止</b>而不是留在目标上继续跑。
     * 少数精简镜像没有 {@code timeout}，命令会以 127 失败并把原因如实回给模型。
     */
    static String wrap(String workdir, String command, long timeoutMs) {
        String body = blank(workdir)
                ? command
                : "cd -- " + quote(workdir) + " && " + command;
        long seconds = Math.max(1L, timeoutMs / 1000L);
        return "timeout -k 5 " + seconds + " sh -lc " + quote(body);
    }

    /** 单引号包裹（内嵌单引号按 {@code '\''} 转义）：命令原文如实传递，不被外层 shell 二次解释 */
    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
