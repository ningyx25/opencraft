package com.swaydy.opencraft.agent.hooks;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次任务（{@link com.swaydy.opencraft.agent.LoopSession}）装配的默认 {@link LoopHook} 组合。
 *
 * <p>对齐 deepseek-harness 的 bundle 装配：loop 驱动本身是通用的，具体能力由挂载的插件/钩子
 * 决定。这里集中声明「内置横切能力」这一组合——新增一个守卫或核心工具 = 写一个 {@link LoopHook}
 * 并在此登记，无需改动 {@code AgentRuntime} 驱动。每次调用都新建一套钩子（各自持有 per-task
 * 状态），跨任务不串。
 *
 * <p>顺序：先核心工具（贡献 schema / 认领调用），后观察型守卫。{@code beforeBatch} 只被
 * AskPlayerHook 用于确认短路；{@code handleTool} 按名字认领，互不冲突；{@code afterTool}/
 * {@code afterBatch}/{@code onFinalText} 各自独立运行。
 */
public final class LoopHooks {
	private LoopHooks() {
	}

	/** 为一次新任务创建整套默认钩子（各钩子持有自己的 per-task 状态）。 */
	public static List<LoopHook> createDefaults() {
		List<LoopHook> hooks = new ArrayList<>();
		// 核心工具：贡献 schema + 认领调用（task_plan 写计划；ask_player 暂停等回答）
		hooks.add(new TaskPlanHook());
		hooks.add(new AskPlayerHook());
		// 观察型守卫（tools/post-execute / agent/turn-stopping）
		hooks.add(new RepeatCallHook());
		hooks.add(new StallHook());
		hooks.add(new CompletionHook());
		return List.copyOf(hooks);
	}
}
