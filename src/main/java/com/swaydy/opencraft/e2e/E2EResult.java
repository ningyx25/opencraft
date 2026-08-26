package com.swaydy.opencraft.e2e;

/**
 * 单个端到端测试任务的结果（纯数据，供报告与控制台输出）。
 *
 * @param taskId     任务 id
 * @param passed     是否通过
 * @param durationMs 用时（毫秒）
 * @param message    结果说明（通过 = 验证内容；失败 = 原因/缺了什么）
 */
public record E2EResult(String taskId, boolean passed, long durationMs, String message) {
	/** 人类可读的摘要行，如 "PASS chop_tree 用时 63s | 原木掉落且背包含 oak_log x3"。 */
	public String summaryLine() {
		return (passed ? "PASS " : "FAIL ") + taskId + " 用时 " + (durationMs / 1000) + "s | " + message;
	}
}
