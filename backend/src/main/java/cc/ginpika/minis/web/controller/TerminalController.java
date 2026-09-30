package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.ai.terminal.TerminalService;
import cc.ginpika.minis.ai.terminal.dto.TerminalDtos;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 命令行终端接口（工作空间页）：目标维护 + 连通性测试 + 试跑 + 执行审计。
 *
 * <p>与潜意识那套的取舍一致：<b>能力面由人配置，使用时机由模型决定</b>。
 * 这里管的是「模型能碰哪些环境、能碰多久、能输出多少」（{@code term_target}），
 * 而「什么时候去执行什么」发生在对话里，页面不做这个决定——所以这里没有「让模型执行」这类接口，
 * 只有人工试跑（{@code /targets/{id}/run}），它走的是与模型完全相同的那条执行路径。
 *
 * <p>没有全局总开关：能力是否可用只看逐目标的 {@code enabled}（{@code term_target}）——
 * 想让模型用哪台环境就开哪台。
 */
@RestController
@RequestMapping("/api/terminal")
public class TerminalController {

    /** 审计列表默认返回条数 */
    private static final int DEFAULT_COMMANDS = 50;

    private final TerminalService terminal;

    public TerminalController(TerminalService terminal) {
        this.terminal = terminal;
    }

    /** 整页视图：全部目标 */
    @GetMapping
    public TerminalDtos.TerminalView get() {
        return terminal.view();
    }

    @PostMapping("/targets")
    public TerminalDtos.TerminalView createTarget(@RequestBody TerminalDtos.TargetRequest req) {
        terminal.create(req);
        return terminal.view();
    }

    @PutMapping("/targets/{id}")
    public TerminalDtos.TerminalView updateTarget(@PathVariable String id,
                                                  @RequestBody TerminalDtos.TargetRequest req) {
        terminal.update(id, req);
        return terminal.view();
    }

    @DeleteMapping("/targets/{id}")
    public TerminalDtos.TerminalView deleteTarget(@PathVariable String id) {
        terminal.delete(id);
        return terminal.view();
    }

    /** 连通性测试：连得上、能执行即通过（不打洞、不起容器） */
    @PostMapping("/targets/{id}/probe")
    public TerminalDtos.ProbeView probe(@PathVariable String id) {
        return terminal.probe(id);
    }

    /** 人工试跑一条命令：与模型走同一条执行路径（同样上护栏、同样记审计） */
    @PostMapping("/targets/{id}/run")
    public TerminalDtos.CommandView run(@PathVariable String id,
                                        @RequestBody TerminalDtos.RunRequest req) {
        return terminal.runManual(id, req == null ? null : req.command());
    }

    /** 最近执行过的命令（倒序）：模型到底在环境里做了些什么 */
    @GetMapping("/commands")
    public List<TerminalDtos.CommandView> commands(
            @RequestParam(defaultValue = "" + DEFAULT_COMMANDS) int limit) {
        return terminal.commands(limit);
    }
}
