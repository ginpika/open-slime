package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.agent.workspace.WorkspaceDtos;
import cc.ginpika.minis.agent.workspace.WorkspaceService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工作空间的文件面（工作空间页）：列目录、文本预览、下载。
 *
 * <p>「有哪些工作空间、怎么增删改测」仍走命令行终端那套接口（{@code /api/terminal}）——
 * 工作空间是基于 terminal 的扩展，环境定义与凭证只有一份，不在这里重复。本控制器只提供
 * terminal 之外新增的只读文件能力，路径参数均为<b>相对起点目录</b>的沙箱路径。
 */
@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaces;

    public WorkspaceController(WorkspaceService workspaces) {
        this.workspaces = workspaces;
    }

    /** 列目录：path 为相对起点目录的路径，缺省（"."）即起点本身 */
    @GetMapping("/{id}/files")
    public WorkspaceDtos.DirView files(@PathVariable String id,
                                       @RequestParam(defaultValue = ".") String path) {
        return workspaces.list(id, path);
    }

    /** 文本预览：超上限截断并标注；二进制文件回类型与大小、不回正文 */
    @GetMapping("/{id}/file")
    public WorkspaceDtos.FileView file(@PathVariable String id,
                                       @RequestParam(defaultValue = ".") String path) {
        return workspaces.read(id, path);
    }

    /** 下载：以附件形式回原始字节（二进制安全） */
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable String id,
                                           @RequestParam(defaultValue = ".") String path) {
        WorkspaceDtos.Download download = workspaces.download(id, path);
        /* filename* 用 RFC 5987 的 UTF-8 形式，中文名也能正确落盘 */
        String encoded = URLEncoder.encode(download.filename(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(download.bytes().length)
                .body(download.bytes());
    }
}
