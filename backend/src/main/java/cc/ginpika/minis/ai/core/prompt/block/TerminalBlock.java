package cc.ginpika.minis.ai.core.prompt.block;

import cc.ginpika.minis.ai.core.prompt.PromptBlock;
import cc.ginpika.minis.ai.core.prompt.PromptLibrary;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 命令行终端的用法规范：约定 run_command / list_terminals 的调用策略与安全边界。
 *
 * <p>单独成块（而不是塞进 {@code ChatGuidanceBlock}）：这段规范只对「开了终端的目标」有意义，
 * 关着的时候它也不该出现在 system 里占用注意力——块可以在提示词页单独改、单独关，
 * 这比在一大段通用增强里夹几句更可控。
 */
@Component
public class TerminalBlock implements PromptBlock {

    public static final String KEY = "terminal";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String name() {
        return "命令行终端";
    }

    @Override
    public String description() {
        return "约定 run_command / list_terminals 的调用策略与安全边界（真实环境里的命令执行）。";
    }

    @Override
    public Set<String> contexts() {
        return Set.of(PromptLibrary.CTX_CHAT);
    }

    @Override
    public String tag() {
        return "terminal";
    }

    @Override
    public String builtin() {
        return """
                【命令行终端】
                你具备直接控制所在环境的能力：通过 run_command 在一台目标环境（本机 docker 容器或远程服务器）里执行命令行，
                用它查看文件与系统状态、跑构建与测试、查日志、按用户要求调整部署。
                它不是模拟：命令在真实环境里生效，所以下列约定是硬约束。

                1. 先看清目标：用 list_terminals 拿到可用目标清单与各自的类型、位置、工作目录，
                再以返回的名字作为 target。不要猜目标名；返回为空或提示未开启时，说明用户还没配置，
                直接告知用户去设置页添加，不要假设环境存在、也不要换个名字反复试。
                2. 每条命令都是一次全新的非交互进程：工作目录与 shell 变量不跨调用保留，
                写下的文件、装上的包会保留。所以需要连续操作时应显式 cd 或在一条命令里串起来，
                不要依赖上一条命令留下的位置。
                3. 输出要读全：结果里会标注 exit 退出码、是否超时被终止、是否因超长被截断。
                被截断时用更精确的命令（grep/head/tail）取需要的那一段，而不是重复执行同一条命令。
                4. 不可逆操作先确认：删除、覆盖、重启、改权限、动生产数据这类操作，
                即使用户提过也要先说明你打算执行什么、影响范围是什么，得到确认再动手。
                5. 被拒不要硬闯：命令被白/黑名单规则拒绝、或目标未启用时，如实告诉用户原因并停手，
                不要改写命令去绕过规则。规则是用户设的，不是障碍。
                6. 回答里给出证据：引用执行结果时说明是在哪台目标上跑的、关键命令是什么；
                把命令输出中的事实与你的推测分开说。
                """;
    }
}
