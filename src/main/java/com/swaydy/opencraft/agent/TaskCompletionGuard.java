package com.swaydy.opencraft.agent;

/**
 * 终止守卫：模型输出纯文本（无工具调用）时校验「任务是否真的完成」，
 * 防止多步任务半途而废（例如助手一边走向目标一边说"正在赶去"就停了——
 * 循环一旦收尾，异步移动会被跟随逻辑召回，任务物理上夭折）。
 *
 * <p>触发条件（满足其一即暂缓收尾）：
 * <ul>
 * <li>任务计划（task_plan）仍有未完成步骤（pending / in_progress）；</li>
 * <li>异步动作仍在途（手动移动 / 挖掘进行中）。</li>
 * </ul>
 *
 * <p>暂缓不是无限的：最多 {@link #MAX_HOLDS} 次（防止模型铁了心收尾时与之对抗），
 * 超过后放行收尾。总结轮（已达 maxToolRounds 的最后一轮）不适用——预算已尽，
 * 应让模型总结。纯 Java、无 Minecraft 依赖，便于 JUnit 单测。
 */
public final class TaskCompletionGuard {
	/** 最多暂缓收尾的次数：超过后放行（不与模型无限对抗）。 */
	public static final int MAX_HOLDS = 2;

	private TaskCompletionGuard() {
	}

	/**
	 * 判定是否暂缓收尾；需要暂缓时返回注入给模型的提醒文本，否则返回 null。
	 *
	 * @param planUnfinished 任务计划是否有未完成步骤
	 * @param asyncInFlight  是否有异步动作（手动移动/挖掘）在途
	 * @param holdsUsed      已暂缓的次数（达到 {@link #MAX_HOLDS} 后放行）
	 * @param planSummary    计划摘要（可空；拼进提醒让模型看到剩余量）
	 */
	public static String holdReminder(boolean planUnfinished, boolean asyncInFlight,
	                                   int holdsUsed, String planSummary) {
		if ((!planUnfinished && !asyncInFlight) || holdsUsed >= MAX_HOLDS) {
			return null;
		}
		StringBuilder why = new StringBuilder();
		if (planUnfinished) {
			why.append("the task plan still has unfinished steps");
			if (planSummary != null && !planSummary.isBlank()) {
				why.append(" (").append(planSummary).append(")");
			}
		}
		if (asyncInFlight) {
			if (why.length() > 0) {
				why.append("; ");
			}
			why.append("an async action (walking/mining) is still running — ending now would abort it");
		}
		return "[System] The task is not finished yet — " + why + ". "
				+ "Continue acting with tools and complete the remaining steps; if you are waiting for an "
				+ "async action, wait for it instead of replying with text. Only reply with plain text when "
				+ "every step is completed or you honestly cannot proceed (then say so clearly).";
	}
}
