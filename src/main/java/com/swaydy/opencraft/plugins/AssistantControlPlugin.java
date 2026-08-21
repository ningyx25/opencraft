package com.swaydy.opencraft.plugins;

import com.google.gson.JsonObject;
import com.swaydy.opencraft.ai.AiCompanionService;

import java.util.List;

/**
 * 基础插件（所有预设都装）：控制助手自身状态。
 *
 * - teleport_to_player：瞬移到主人身边（跨维度）。
 *
 * 跟随模式：助手默认自动跟随主人；玩家下达任务指令后退出跟随专注执行，
 * 指令完成自动回到跟随（无需模型干预，由服务端 AgentRuntime 切换）。
 * 因此本插件不需要 set_mode 工具——跟随是自动的。
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
		return "你平时会自动跟随主人（主人走到哪你跟到哪）；当主人给你下达任务指令时，你会停下跟随、"
				+ "专注执行任务，任务完成后自动回到跟随状态。你也可以使用 teleport_to_player 瞬移到主人身边。";
	}

	private ToolResult teleport(ToolContext ctx, JsonObject args) {
		AiCompanionService.teleportAssistantToPlayer(ctx.owner(), ctx.assistant());
		return ToolResult.ok("我已传送到你身边。");
	}
}