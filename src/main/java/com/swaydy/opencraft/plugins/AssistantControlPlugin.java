package com.swaydy.opencraft.plugins;

import com.google.gson.JsonObject;
import com.swaydy.opencraft.agent.AssistantPlugin;
import com.swaydy.opencraft.agent.ToolContext;
import com.swaydy.opencraft.agent.ToolDefinition;
import com.swaydy.opencraft.agent.ToolResult;
import com.swaydy.opencraft.ai.AiCompanionService;
import com.swaydy.opencraft.entity.AiAssistantEntity;
import com.swaydy.opencraft.entity.FollowAssistantOwnerGoal;

import java.util.List;

/**
 * 基础插件（所有预设都装）：控制助手自身状态。
 *
 * - set_mode：切换跟随/待命；
 * - teleport_to_player：瞬移到主人身边（跨维度）；
 * - registerGoals：注册跟随主人的 Goal。
 */
public class AssistantControlPlugin implements AssistantPlugin {
	@Override
	public String id() {
		return "assistant_control";
	}

	@Override
	public List<ToolDefinition> tools() {
		JsonObject modeProps = new JsonObject();
		modeProps.add("mode", ToolSchema.prop("string",
				"跟随模式：\"follow\" 表示一直跟着主人走，\"stay\" 表示在原地待命不跟随。"));
		return List.of(
				new ToolDefinition("set_mode",
						"切换助手的跟随/待命模式。mode 取 \"follow\"（跟随主人）或 \"stay\"（原地待命）。",
						ToolSchema.object(modeProps, "mode"),
						this::setMode),
				new ToolDefinition("teleport_to_player",
						"让助手瞬间传送到主人身边（支持跨维度）。",
						ToolSchema.object(new JsonObject()),
						this::teleport));
	}

	@Override
	public String systemPromptFragment() {
		return "助手默认会跟随主人；玩家可以要求你切换跟随/待命（set_mode）或传送到玩家身边（teleport_to_player）。";
	}

	@Override
	public void registerGoals(AiAssistantEntity assistant) {
		// 优先级 1：跟随主人（仅 general 类预设通过本插件启用）
		assistant.addAssistantGoal(1, new FollowAssistantOwnerGoal(assistant));
	}

	private ToolResult setMode(ToolContext ctx, JsonObject args) {
		ToolArgs a = new ToolArgs(args);
		String mode = a.strOf("mode", "").toLowerCase(java.util.Locale.ROOT);
		if (!mode.equals("follow") && !mode.equals("stay")) {
			return ToolResult.error("mode 参数必须是 \"follow\" 或 \"stay\"，收到: \"" + mode + "\"。");
		}
		boolean following = mode.equals("follow");
		ctx.assistant().setFollowing(following);
		return ToolResult.ok(following ? "已切换为跟随模式，我会一直跟着你。" : "已切换为待命模式，我在这里等你。");
	}

	private ToolResult teleport(ToolContext ctx, JsonObject args) {
		AiCompanionService.teleportAssistantToPlayer(ctx.owner(), ctx.assistant());
		return ToolResult.ok("我已传送到你身边。");
	}
}