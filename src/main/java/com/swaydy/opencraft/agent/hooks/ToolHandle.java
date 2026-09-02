package com.swaydy.opencraft.agent.hooks;

import com.swaydy.opencraft.plugins.ToolResult;

/**
 * 核心工具拦截结果（{@link LoopHook#handleTool} 返回）：
 * 钩子要么认领某次核心工具调用（{@link #handled}），要么放行给插件工具注册表。
 *
 * <p>参考 deepseek-harness：{@code todo_write} / {@code ask_user} 等与 loop 深度协作的能力
 * 也是普通工具，但能访问 agent/session。这里核心工具（task_plan / ask_player）需要读写
 * 单次任务状态（计划）或请求暂停循环，故由钩子在统一的工具分派路径上认领。
 *
 * @param result      认领时喂回给模型的结果；{@code null} 表示未认领（notHandled）
 * @param askQuestion 非 null = ask_player 有效提问：结果入列后循环暂停等待玩家回答，
 *                    且本批后续工具不再执行（提问优先，破坏性动作不应先于确认发生）
 */
public record ToolHandle(ToolResult result, String askQuestion) {
	/** 未认领：放行给插件工具注册表分派。 */
	public static ToolHandle notHandled() {
		return new ToolHandle(null, null);
	}

	/** 认领一次核心工具调用，同步返回结果（不暂停）。 */
	public static ToolHandle handled(ToolResult result) {
		return new ToolHandle(result, null);
	}

	/** 认领 ask_player：结果入列后暂停循环等待玩家回答。 */
	public static ToolHandle ask(ToolResult result, String question) {
		return new ToolHandle(result, question);
	}

	public boolean isHandled() {
		return result != null;
	}

	/** 是否为有效提问（要求循环暂停等待玩家回答）。 */
	public boolean pausesForAnswer() {
		return askQuestion != null;
	}
}
