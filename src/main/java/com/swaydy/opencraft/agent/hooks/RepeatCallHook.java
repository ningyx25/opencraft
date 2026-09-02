package com.swaydy.opencraft.agent.hooks;

import com.swaydy.opencraft.agent.LoopSession;
import com.swaydy.opencraft.agent.RepeatToolGuard;
import com.swaydy.opencraft.ai.LlmClient;

import java.util.List;

/**
 * 重复工具调用守卫钩子（封装 {@link RepeatToolGuard}，对齐 deepseek-harness 的
 * {@code repeat-tool-reminder} 插件：监听 {@code tools/post-execute}，observe-and-enrich，
 * 不否决、不改写）。
 *
 * <p>每个工具执行后，若该调用计入重复链（{@link ToolExec#countForRepeat()}），喂给守卫；
 * 撞阈值时把提醒作为一条 user 消息追加到 {@code out}，随本批结果进入下一轮请求，
 * 打断「连续、完全相同」的死循环。冗余 goto（等待到达的再确认）与成功的 task_plan
 * 由 runtime 标记为不计入。
 */
public final class RepeatCallHook implements LoopHook {
	private final RepeatToolGuard guard = new RepeatToolGuard();

	@Override
	public void afterTool(LoopSession session, ToolExec exec, List<LlmClient.Message> out) {
		if (!exec.countForRepeat()) {
			return;
		}
		String reminder = guard.observe(exec.name(), exec.call().arguments());
		if (reminder != null) {
			out.add(LlmClient.Message.user(reminder));
		}
	}
}
