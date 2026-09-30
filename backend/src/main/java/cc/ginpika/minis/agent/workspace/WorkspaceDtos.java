package cc.ginpika.minis.agent.workspace;

import java.util.List;

/**
 * 工作空间文件面的请求/响应（{@code web.controller.WorkspaceController} ↔ 工作空间页）。
 *
 * <p>工作空间本身（有哪些环境、凭证是什么）沿用命令行终端模块的 {@code TerminalDtos}——工作空间
 * 是「基于 terminal 的扩展」，不另起一套环境定义；这里只描述 terminal 之外新增的那部分：
 * 目录浏览、文本预览与下载。
 *
 * <p>路径一律是<b>相对工作空间起点目录</b>的沙箱路径（由目录项名字逐级拼成，不接受 {@code ..} 与绝对路径），
 * 因此这些 DTO 里的 path 都是安全的相对串，回给前端可直接驱动面包屑。
 */
public final class WorkspaceDtos {

    /** 目录里的一项：名字、是否目录、字节大小、修改时间（ls 原样字符串）、是否符号链接 */
    public record FileEntry(String name, boolean dir, long size, String modified, boolean link) {
    }

    /** 一次目录浏览的结果：当前相对路径 + 目录项（目录在前，随后按名字排序） */
    public record DirView(String path, List<FileEntry> entries) {
    }

    /**
     * 一次文本预览：{@code truncated}=读取达到上限（只给了前半段）；
     * {@code binary}=按二进制判定（此时 {@code content} 为 null，前端不渲染正文只提供下载）。
     */
    public record FileView(String path, String name, long size, boolean truncated, boolean binary,
                           String content) {
    }

    /** 下载结果：建议文件名 + 原始字节 */
    public record Download(String filename, byte[] bytes) {
    }

    private WorkspaceDtos() {
    }
}
