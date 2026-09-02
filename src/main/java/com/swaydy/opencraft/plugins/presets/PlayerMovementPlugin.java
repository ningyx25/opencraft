package com.swaydy.opencraft.plugins.presets;

import com.google.gson.JsonObject;
import com.swaydy.opencraft.plugins.ToolDefinition;
import com.swaydy.opencraft.plugins.ToolSchema;

import java.util.List;

/**
 * 移动能力族插件（capability family，参考 deepseek-harness 的 shell/movement 能力分包）：
 * 让玩家形态助手在世界里走动/停止/瞬移/跳跃。移动/瞬移均受配置的最大距离缰绳约束；
 * goto 是异步动作（walk-to，到达经 [Event] 续轮），teleport 同步兜底。
 *
 * <p>实现（玩家 bot 操作链路）在包内 {@link PlayerActionMechanics} 的静态方法；本类只贡献
 * 模型可见的工具 surface（schema/描述）与提示词片段，可与其它能力族插件任意组合。
 */
public final class PlayerMovementPlugin implements AssistantPlugin {
	@Override
	public String id() {
		return "player_movement";
	}

	@Override
	public List<ToolDefinition> tools() {
		JsonObject coords = new JsonObject();
		coords.add("x", ToolSchema.prop("integer", "Target X coordinate (absolute, integer)"));
		coords.add("y", ToolSchema.prop("integer", "Target Y coordinate (absolute, integer)"));
		coords.add("z", ToolSchema.prop("integer", "Target Z coordinate (absolute, integer)"));
		return List.of(
				new ToolDefinition("player_goto",
						"Have the assistant (as a player) walk to the given coordinates (absolute x,y,z). Asynchronous: "
								+ "the call returns immediately and the conversation pauses; when the assistant arrives, "
								+ "the outcome arrives automatically as an [Event] message — never re-issue the same goto while waiting.",
						ToolSchema.object(coords, "x", "y", "z"),
						PlayerActionMechanics::gotoTool),
				new ToolDefinition("player_stop",
						"Cancel the assistant's current movement and mining, making it stop.",
						ToolSchema.object(new JsonObject()),
						PlayerActionMechanics::stopTool),
				new ToolDefinition("player_teleport",
						"Instantly teleport the assistant (as a player) to the given absolute coordinates (x,y,z) "
								+ "in the current dimension. Use this when walking is impractical (steep cliffs, "
								+ "lava/water gaps, or when player_goto keeps getting stuck). Cancels any current "
								+ "movement/mining. Synchronous: the teleport happens immediately. "
								+ "Still bounded by the maxDistance leash from your current position (same limit as player_goto).",
						ToolSchema.object(coords, "x", "y", "z"),
						PlayerActionMechanics::teleportTool),
				new ToolDefinition("player_jump",
						"Have the assistant jump in place (to hop over 1-block steps/small gaps; can be combined with player_goto "
								+ "for a running jump; only takes effect when on the ground).",
						ToolSchema.object(new JsonObject()),
						PlayerActionMechanics::jump));
	}

	@Override
	public String systemPromptFragment() {
		return """
				## Player Form

				You have joined the Minecraft server as a real player: full player inventory, equipment slots, and player-style actions.

				- **`player_goto` / `player_stop`** — walk to coordinates / stop (stop also cancels mining)
				- **`player_teleport`** — instantly teleport to any coordinates in the current dimension (use it when walking is impractical: cliffs, lava/water, deep underground, or repeated goto stuck); cancels current movement/mining
				- **`player_jump`** — jump over 1-block steps or small gaps (combine with a movement target for a running jump)""";
	}
}
