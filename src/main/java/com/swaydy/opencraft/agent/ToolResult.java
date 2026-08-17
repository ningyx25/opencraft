package com.swaydy.opencraft.agent;

/**
 * 工具执行的结果文本（喂回给 LLM 看，模型据此决定下一步）。
 *
 * @param ok      是否执行成功（失败时模型应读 message 自我纠正）
 * @param message 给模型看的执行结果/错误说明（可含坐标、物品名、数量等）
 */
public record ToolResult(boolean ok, String message) {
	/** 执行成功。 */
	public static ToolResult ok(String message) {
		return new ToolResult(true, message);
	}

	/** 执行失败（参数错误/目标不可达/权限不允许等），message 说明原因。 */
	public static ToolResult error(String message) {
		return new ToolResult(false, message);
	}
}