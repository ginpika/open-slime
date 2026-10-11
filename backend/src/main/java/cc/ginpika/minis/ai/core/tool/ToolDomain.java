package cc.ginpika.minis.ai.core.tool;

/**
 * 工具的业务领域（能力平面 STEP 3）：成员页「可用工具」按它归类分 Tab 展示，
 * 同时是分层路由的<b>选择单位</b>——一次调用按意图先选域、再注入该域的工具，而不是常驻全量。
 *
 * <p>领域由工具自己声明（{@link AiTool#domain()}），与工具面（{@link ToolSurface}）、风险等级
 * （{@link ToolRisk}）正交：工具面决定「模型这次能看见哪些工具」，领域决定「属于哪种能力」
 * （既是页面分类，也是路由单位），风险等级决定「要不要授权」。三者互不影响。
 *
 * <p>{@code fs / editor / lsp / git / test} 是 IDE 能力域，为 STEP 5 预留——当前还没有工具落在
 * 这些域上（空域不会产生页面 Tab，也无从被路由选中），IDE 工具落地时直接声明对应域即可。
 */
public enum ToolDomain {

    /** 文档：标题 / 关键词检索、正文阅读、文档撰写 */
    DOC("doc", "文档"),

    /** 图谱：实体与关系（三元组）的检索、浏览与增删改 */
    GRAPH("graph", "图谱"),

    /** 记忆：事件流回忆、向量回填 */
    MEMORY("memory", "记忆"),

    /** 终端：在工作空间上执行命令、查看可用环境 */
    TERMINAL("terminal", "终端"),

    /** IDE · 文件：工作区文件树与读写（STEP 5 预留） */
    FS("fs", "文件"),

    /** IDE · 编辑器：按位置读写代码、格式化（STEP 5 预留） */
    EDITOR("editor", "编辑器"),

    /** IDE · 代码智能：诊断 / 跳转 / 补全（STEP 5 预留） */
    LSP("lsp", "代码智能"),

    /** IDE · 版本控制：状态 / diff / 提交（STEP 5 预留） */
    GIT("git", "版本控制"),

    /** IDE · 测试：跑测试与构建（STEP 5 预留） */
    TEST("test", "测试"),

    /** 其它：未声明领域时的兜底（编排 / 控制类工具） */
    GENERAL("general", "其他");

    private final String key;
    private final String label;

    ToolDomain(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /** 稳定标识（前端分组 / Tab key） */
    public String key() {
        return key;
    }

    /** 展示名（前端 Tab 标题） */
    public String label() {
        return label;
    }
}
