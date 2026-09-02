package com.swaydy.opencraft.plugins.presets;

import com.google.gson.JsonObject;
import com.swaydy.opencraft.plugins.ToolDefinition;
import com.swaydy.opencraft.plugins.ToolSchema;

import java.util.List;

/**
 * 世界交互能力族插件：{@code player_mine} / {@code player_place}——用原版真实玩家的
 * 破坏/放置链路（{@code ServerPlayerGameMode} 挖掘进度、{@code useItemOn} 贴面放置）。
 * 两者都是异步动作（walk-to + 完成经 [Event] 续轮）。实现见包内 {@link PlayerActionMechanics}。
 */
public final class PlayerWorldPlugin implements AssistantPlugin {
	@Override
	public String id() {
		return "player_world";
	}

	@Override
	public List<ToolDefinition> tools() {
		JsonObject mineProps = new JsonObject();
		mineProps.add("x", ToolSchema.prop("integer", "Block X coordinate"));
		mineProps.add("y", ToolSchema.prop("integer", "Block Y coordinate"));
		mineProps.add("z", ToolSchema.prop("integer", "Block Z coordinate"));
		JsonObject placeProps = new JsonObject();
		placeProps.add("x", ToolSchema.prop("integer", "X coordinate of the block to place against (the adjacent block)"));
		placeProps.add("y", ToolSchema.prop("integer", "Y coordinate of the block to place against"));
		placeProps.add("z", ToolSchema.prop("integer", "Z coordinate of the block to place against"));
		placeProps.add("face", ToolSchema.prop("string",
				"Which face to place on: up/down/north/south/east/west (default up)"));
		placeProps.add("sneak", ToolSchema.prop("boolean",
				"True to place like shift-clicking (place against functional blocks such as chests/furnaces "
						+ "instead of opening them). Default false."));
		return List.of(
				new ToolDefinition("player_mine",
						"Have the assistant (as a player) mine the block at the given coordinates: walk up to it and break it with the "
							+ "main-hand tool like a player; drops fall out as items and are auto-picked into the assistant's inventory. "
							+ "Asynchronous: the loop pauses and the outcome (done / aborted, picked-up items) arrives automatically "
							+ "as an [Event] message — never re-issue while waiting. If the block is deep underground, walking only "
							+ "gets the assistant above it — dig down step by step instead. "
							+ "Cannot mine air, bedrock, or containers (chests/furnaces etc.).",
						ToolSchema.object(mineProps, "x", "y", "z"),
						PlayerActionMechanics::mine),
				new ToolDefinition("player_place",
						"Have the assistant (as a player) place a block at the given position with its main-hand item: place it against the "
							+ "face of the block at (x,y,z). Requires a placeable item in the main hand (e.g. stone/planks). "
							+ "If far away, the assistant walks over first and the outcome arrives automatically as an [Event] message.",
						ToolSchema.object(placeProps, "x", "y", "z"),
						PlayerActionMechanics::place));
	}

	@Override
	public String systemPromptFragment() {
		return """
				## Mining & Placing

				- **`player_mine` / `player_place`** — break/place blocks the player way (mining takes real time depending on the tool — pick the right tool; place with `sneak=true` to place against chests/furnaces instead of opening them; drops go straight into the inventory)

				Tool results begin with `[tool success/failure]` — read the marker first; never assume a tool succeeded;
				on failure try a different approach rather than retrying identically.""";
	}
}
