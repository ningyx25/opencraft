package com.swaydy.opencraft.plugins;

import com.google.gson.JsonObject;
import com.swaydy.opencraft.agent.AssistantPlugin;
import com.swaydy.opencraft.agent.ToolContext;
import com.swaydy.opencraft.agent.ToolDefinition;
import com.swaydy.opencraft.agent.ToolResult;
import com.swaydy.opencraft.ai.AiCompanionService;

import java.util.List;

/**
 * 基础插件（所有预设都装）：控制助手自身状态。
 *
 * - teleport_to_player：瞬移到主人身边（跨维度）。
 *
 * （“跟随/待命”模式已整体移除：助手不再自动跟随，因此没有 set_mode 工具；
 * 助手召唤后停留在原地，只受显式移动指令驱动。）
 */
public class AssistantControlPlugin implements AssistantPlugin {
	@Override
	public String id() {
		return "assistant_control";
	}

	@Override
	public List<ToolDefinition> tools() {
		return List.of(
				new ToolDefinition("teleport_to_player",
						"让助手瞬间传送到主人身边（支持跨维度）。",
						ToolSchema.object(new JsonObject()),
						this::teleport));
	}

	@Override
	public String systemPromptFragment() {
		return "助手召唤后停留在原地，不会自动跟随；玩家可以要求你传送到玩家身边（teleport_to_player）。";
	}

	private ToolResult teleport(ToolContext ctx, JsonObject args) {
		AiCompanionService.teleportAssistantToPlayer(ctx.owner(), ctx.assistant());
		return ToolResult.ok("我已传送到你身边。");
	}
}