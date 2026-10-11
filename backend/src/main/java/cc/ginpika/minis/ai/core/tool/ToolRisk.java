package cc.ginpika.minis.ai.core.tool;

/**
 * 工具的风险等级（能力平面 STEP 3）：这个工具一旦被调用，能把世界改到什么程度。
 *
 * <p>与 {@link ToolSurface}（模型这次能看见什么）、{@link ToolDomain}（归到哪一类）正交：
 * 面决定「可不可见」，域决定「属于哪种能力」，风险等级决定「要不要授权 / 要不要护栏」。
 * 只读检索永远安全，写入会改数据，执行能改运行环境——三档由工具自己声明（{@link AiTool#risk()}），
 * 授权与沙箱据此决定放不放行。
 */
public enum ToolRisk {

    /** 只读：检索 / 浏览 / 读文件，不改动任何状态 */
    READ("read", "只读"),

    /** 写入：改知识库 / 图谱 / 文档 / 文件等持久化数据 */
    WRITE("write", "写入"),

    /** 执行：在真实环境里跑命令（可产生任何副作用，风险最高） */
    EXEC("exec", "执行");

    private final String key;
    private final String label;

    ToolRisk(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /** 稳定标识 */
    public String key() {
        return key;
    }

    /** 展示名 */
    public String label() {
        return label;
    }
}
