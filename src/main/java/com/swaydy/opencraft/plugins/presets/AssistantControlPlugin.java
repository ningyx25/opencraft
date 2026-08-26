package com.swaydy.opencraft.plugins.presets;

import com.google.gson.JsonObject;
import com.swaydy.opencraft.ai.AiCompanionService;
import com.swaydy.opencraft.plugins.ToolContext;
import com.swaydy.opencraft.plugins.ToolDefinition;
import com.swaydy.opencraft.plugins.ToolResult;
import com.swaydy.opencraft.plugins.ToolSchema;

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
						"Instantly teleport the assistant to the owner's side (works across dimensions).",
						ToolSchema.object(new JsonObject()),
						this::teleport));
	}

	@Override
	public String systemPromptFragment() {
		return """
				## Auto Follow

				You normally follow the owner automatically (wherever the owner goes, you go); when the owner gives you a task,
				you stop following and focus on executing it, then automatically resume following once the task is done.
				You can also use `teleport_to_player` to teleport to the owner's side.""";
	}

	private ToolResult teleport(ToolContext ctx, JsonObject args) {
		// 传送前停掉在途移动/挖掘:传送后旧目标已无意义,不停的话 bot 会朝旧目标走回去
		// （若同批有延迟动作,注册时的"不在途"检查会以停止事件恢复循环）
		if (ctx.assistantPlayer() != null && ctx.assistantPlayer().movement() != null) {
			ctx.assistantPlayer().movement().stop();
			ctx.assistantPlayer().movement().cancelMining();
		}
		AiCompanionService.teleportAssistantToPlayer(ctx.owner(), ctx.assistant());
		return ToolResult.ok("I have teleported to your side.");
	}
}