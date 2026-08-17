package com.swaydy.opencraft.agent;

import com.swaydy.opencraft.entity.AiAssistantEntity;

import java.util.List;

/**
 * AI 插件：一组内聚的能力单元。
 *
 * “万物皆插件”：助手的能力 = 其 Agent 预设装配的插件之和。一个插件可以：
 * - 贡献工具（{@link #tools()}，模型通过 function calling 调用）；
 * - 贡献 system 提示词片段（{@link #systemPromptFragment()}，告诉模型有哪些工具、怎么用）；
 * - 贡献游戏上下文片段（{@link #gameContextFragment(Object)}，当前状态/正在执行的任务）；
 * - 注册实体 AI Goal（{@link #registerGoals(AiAssistantEntity)}，如跟随、任务 Goal）。
 *
 * 插件按约定遵守的运行时规则：
 * - 工具在服务端线程执行、不阻塞主线程（长任务只下达指令，立即返回）；
 * - 异步任务由助手实体自己的 tick 驱动，模型后续用感知工具观察结果。
 */
public interface AssistantPlugin {
	/** 插件唯一 id，如 "movement"。 */
	String id();

	/** 本插件贡献的工具；无工具的插件（纯 Goal/上下文）返回空列表。 */
	default List<ToolDefinition> tools() {
		return List.of();
	}

	/** 追加到 system 提示词的能力说明片段（告诉模型有哪些工具、怎么用）；可为 null。 */
	default String systemPromptFragment() {
		return null;
	}

	/** 追加到游戏上下文的状态片段（如助手当前坐标/正在执行的任务）；可为 null。 */
	default String gameContextFragment(ToolContext ctx) {
		return null;
	}

	/** 给助手实体注册的 AI Goal（如跟随、挖掘任务 Goal）；默认无。 */
	default void registerGoals(AiAssistantEntity assistant) {
	}
}