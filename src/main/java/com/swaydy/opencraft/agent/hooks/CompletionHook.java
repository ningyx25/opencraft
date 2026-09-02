package com.swaydy.opencraft.agent.hooks;

import com.swaydy.opencraft.agent.LoopSession;
import com.swaydy.opencraft.agent.TaskCompletionGuard;

/**
 * 终止守卫钩子（封装 {@link TaskCompletionGuard}，对齐 deepseek-harness 的
 * {@code agent/turn-stopping}：监听器可否决本轮结束）。
 *
 * <p>模型输出纯文本（无工具调用、非总结轮）时校验「任务真的完成了吗」：计划仍有未完成步骤，
 * 或异步动作（手动移动/挖掘）仍在途，则暂缓收尾（最多 {@link TaskCompletionGuard#MAX_HOLDS} 次，
 * 不与铁了心收尾的模型对抗）——runtime 把文本当中间消息广播、注入提醒续轮，防止
 * 「一边走向目标一边说正在赶路」就停、任务被跟随召回半途而废。
 */
public final class CompletionHook implements LoopHook {
	@Override
	public HoldDecision onFinalText(LoopSession session, String text) {
		boolean planUnfinished = session.plan != null && session.plan.hasUnfinished();
		String reminder = TaskCompletionGuard.holdReminder(
				planUnfinished,
				session.asyncActionInFlight(),
				session.terminalHolds,
				session.plan == null ? null : session.plan.summary());
		return reminder == null ? HoldDecision.finish() : HoldDecision.hold(reminder);
	}
}
