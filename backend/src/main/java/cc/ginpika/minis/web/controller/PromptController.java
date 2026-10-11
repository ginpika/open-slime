package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.ai.core.prompt.PromptManager;
import cc.ginpika.minis.ai.core.prompt.dto.PromptDtos;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提示词页（潜意识 · 提示词）：内置提示词的用户版本、生效选择与拼装顺序。
 *
 * <p>对话 / 圆桌提示词已逐 Agent 化（见 {@code AgentController} 的 {@code /peers/{id}/prompts}），
 * 故本页只展示<b>开发者选项</b>（流水线上的自动环节）——读写统一经 {@link PromptManager}
 * （提示词子系统的唯一门面）；所有写操作后都返回最新全量视图，前端直接覆盖即可。
 */
@RestController
@RequestMapping("/api/prompts")
public class PromptController {

    private final PromptManager prompts;

    public PromptController(PromptManager prompts) {
        this.prompts = prompts;
    }

    /** 全部上下文（仅开发者选项）：含提示词位、拼装顺序、用户版本、当前生效项 */
    @GetMapping
    public PromptDtos.PromptsView view() {
        return prompts.viewDevOnly();
    }

    /** 为某提示词位新增一份用户版本 */
    @PostMapping("/slots/{slot}/versions")
    public PromptDtos.PromptsView createVersion(@PathVariable String slot,
                                                @RequestBody PromptDtos.VersionRequest req) {
        prompts.createVersion(slot, req);
        return prompts.viewDevOnly();
    }

    /** 改名 / 改正文 */
    @PutMapping("/versions/{id}")
    public PromptDtos.PromptsView updateVersion(@PathVariable String id,
                                                @RequestBody PromptDtos.VersionRequest req) {
        prompts.updateVersion(id, req);
        return prompts.viewDevOnly();
    }

    @DeleteMapping("/versions/{id}")
    public PromptDtos.PromptsView deleteVersion(@PathVariable String id) {
        prompts.deleteVersion(id);
        return prompts.viewDevOnly();
    }

    /** 选定某提示词位生效的版本；versionId 为空或 builtin = 用内置 */
    @PutMapping("/slots/{slot}/binding")
    public PromptDtos.PromptsView bind(@PathVariable String slot,
                                       @RequestBody PromptDtos.BindRequest req) {
        prompts.bind(slot, req);
        return prompts.viewDevOnly();
    }

    /** 保存某提示词位当前生效的正文（便签编辑器直接改正文）：已绑定版本则改它，仍是内置则自动分叉 */
    @PutMapping("/slots/{slot}/content")
    public PromptDtos.PromptsView saveContent(@PathVariable String slot,
                                              @RequestBody PromptDtos.ContentRequest req) {
        prompts.saveContent(slot, req);
        return prompts.viewDevOnly();
    }

    /** 覆盖某上下文内提示词位的拼装顺序（拖拽调整位置） */
    @PutMapping("/contexts/{context}/order")
    public PromptDtos.PromptsView saveOrder(@PathVariable String context,
                                            @RequestBody PromptDtos.OrderRequest req) {
        prompts.saveOrder(context, req);
        return prompts.viewDevOnly();
    }
}
