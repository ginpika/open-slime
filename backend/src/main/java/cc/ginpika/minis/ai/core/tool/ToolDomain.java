package cc.ginpika.minis.ai.core.tool;

/**
 * 工具的业务领域：成员页「可用工具」按它归类分 Tab 展示。
 *
 * <p>领域由工具自己声明（{@link AiTool#domain()}），与工具面（{@link ToolSurface}）正交：
 * 工具面决定「模型这次能看见哪些工具」（参与调用），领域只决定「页面上归到哪一类」（纯展示），
 * 二者互不影响——同一个工具可以既落在对话面、又属于「图谱」领域。
 */
public enum ToolDomain {

    /** 文档：标题 / 关键词检索、正文阅读、文档撰写 */
    DOC("doc", "文档"),

    /** 图谱：实体与关系（三元组）的检索、浏览与增删改 */
    GRAPH("graph", "图谱"),

    /** 记忆：事件流回忆、向量回填 */
    MEMORY("memory", "记忆"),

    /** 其它：未声明领域时的兜底 */
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
