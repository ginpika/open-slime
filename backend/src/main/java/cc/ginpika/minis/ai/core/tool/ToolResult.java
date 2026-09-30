package cc.ginpika.minis.ai.core.tool;

/**
 * 一次工具调用的结果——工具名、调用参数原始 JSON、回给模型的内容、是否写知识库
 * （写库的应记入执行留痕的写库清单）。
 *
 * <p>全项目共用：对话、执行引擎、子代理、潜意识四处都拿它把一次工具调用落地。
 */
public record ToolResult(String name, String args, String content, boolean write) {
}
