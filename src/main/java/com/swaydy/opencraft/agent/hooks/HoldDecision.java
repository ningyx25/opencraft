package com.swaydy.opencraft.agent.hooks;

/**
 * 终止守卫决定（{@link LoopHook#onFinalText} 返回）：模型输出纯文本（无工具调用）时，
 * 是否暂缓收尾。参考 deepseek-harness 的 {@code agent/turn-stopping}——监听器可否决本轮结束。
 *
 * @param hold     true = 任务未完成，暂缓收尾、注入提醒续轮；false = 正常收尾
 * @param reminder hold 时注入给模型的提醒文本
 */
public record HoldDecision(boolean hold, String reminder) {
	/** 放行收尾：这是最终回复。 */
	public static HoldDecision finish() {
		return new HoldDecision(false, null);
	}

	/** 暂缓收尾：任务仍在进行，注入 {@code reminder} 后继续循环。 */
	public static HoldDecision hold(String reminder) {
		return new HoldDecision(true, reminder);
	}
}
