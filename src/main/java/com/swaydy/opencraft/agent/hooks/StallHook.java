package com.swaydy.opencraft.agent.hooks;

import com.swaydy.opencraft.agent.LoopSession;
import com.swaydy.opencraft.agent.StallGuard;
import com.swaydy.opencraft.ai.LlmClient;

import java.util.List;

/**
 * 停滞守卫钩子（封装 {@link StallGuard}）：一批工具执行完后，若连续多轮只调用纯观察工具
 * （player_find / player_inventory / player_container_list）而世界/背包毫无变化，
 * 注入一次提醒让模型「给结论收尾，或执行真实动作」，打断纯观察空转。
 *
 * <p>「做了实事」的判定（与旧内联一致）：task_plan 成功（{@code session.planUpdatedThisRound}）
 * ，或本批调用了任意非只读、非 task_plan 的工具。常规观察信息已由 Assistant State 上下文每轮
 * 自带，不占工具调用。
 */
public final class StallHook implements LoopHook {
	private static final String TASK_PLAN = TaskPlanHook.TOOL_NAME;

	private final StallGuard guard = new StallGuard();

	@Override
	public void afterBatch(LoopSession session, List<String> executedNames,
	                       List<LlmClient.Message> out) {
		boolean anyAffecting = session.planUpdatedThisRound
				|| executedNames.stream().anyMatch(n -> !StallGuard.isReadOnly(n) && !TASK_PLAN.equals(n));
		String nudge = guard.observe(executedNames, anyAffecting);
		if (nudge != null) {
			out.add(LlmClient.Message.user(nudge));
		}
	}
}
