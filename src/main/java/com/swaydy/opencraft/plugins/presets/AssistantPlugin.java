package com.swaydy.opencraft.plugins.presets;

import com.swaydy.opencraft.plugins.ToolContext;
import com.swaydy.opencraft.plugins.ToolDefinition;

import java.util.List;

/**
 * AI 插件：一组内聚的能力单元。
 *
 * “万物皆插件”：助手的能力 = 其 Agent 预设装配的插件之和。一个插件可以：
 * - 贡献工具（{@link #tools()}，模型通过 function calling 调用）；
 * - 贡献 system 提示词片段（{@link #systemPromptFragment()}，告诉模型有哪些工具、怎么用）；
 * - 贡献游戏上下文片段（{@link #gameContextFragment(ToolContext)}，插件自有的状态/进度，
 *   如冷却、任务队列；玩家/助手基础状态由核心的 agent.Prompts 统一提供，不在此贡献）。
 *
 * 插件按约定遵守的运行时规则：
 * - 工具在服务端线程执行、不阻塞主线程（长任务只下达指令，立即返回）；
 * - 长动作由助手自己的 tick 驱动，模型后续用观察工具查看结果。
 */
public interface AssistantPlugin {
	/** 插件唯一 id，如 "player_actions"。 */
	String id();

	/** 本插件贡献的工具；无工具的插件（纯上下文）返回空列表。 */
	default List<ToolDefinition> tools() {
		return List.of();
	}

	/** 追加到 system 提示词的能力说明片段（告诉模型有哪些工具、怎么用）；可为 null。 */
	default String systemPromptFragment() {
		return null;
	}

	/** 追加到游戏上下文的插件自有状态片段（如冷却/任务队列等插件维护的状态）；可为 null。玩家/助手基础状态由核心提供。 */
	default String gameContextFragment(ToolContext ctx) {
		return null;
	}
}
