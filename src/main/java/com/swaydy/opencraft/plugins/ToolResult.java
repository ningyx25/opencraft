package com.swaydy.opencraft.plugins;

/**
 * 工具执行的结果文本（喂回给 LLM 看，模型据此决定下一步）。
 *
 * @param ok       是否执行成功（失败时模型应读 message 自我纠正）
 * @param message  给模型看的执行结果/错误说明（可含坐标、物品名、数量等）
 * @param deferred 异步动作已启动（goto/mine/place 的走路/挖掘）:message 只是
 *                 "进行中"提示,真实结果稍后由 AgentRuntime 以 {@code [Event]}
 *                 user 消息注入并自动续轮——循环在此期间暂停等待
 */
public record ToolResult(boolean ok, String message, boolean deferred) {
	/** 工具执行成功（同步完成,message 即最终结果）。 */
	public static ToolResult ok(String message) {
		return new ToolResult(true, message, false);
	}

	/** 工具执行失败（参数错误/目标不可达/权限不允许等），message 说明原因。 */
	public static ToolResult error(String message) {
		return new ToolResult(false, message, false);
	}

	/**
	 * 异步动作已启动:调用立即返回,message 为"进行中"提示;
	 * 真实结果（到达/挖掘完成/中止）稍后以 {@code [Event]} 消息送达。
	 */
	public static ToolResult deferred(String message) {
		return new ToolResult(true, message, true);
	}
}
