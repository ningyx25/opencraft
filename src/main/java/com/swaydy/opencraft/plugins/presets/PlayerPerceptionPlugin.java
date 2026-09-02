package com.swaydy.opencraft.plugins.presets;

import com.google.gson.JsonObject;
import com.swaydy.opencraft.plugins.ToolDefinition;
import com.swaydy.opencraft.plugins.ToolSchema;

import java.util.List;

/**
 * 感知/定位能力族插件：{@code player_find}——按关键词/ID 找方块/实体/掉落物，
 * 返回精确坐标 + 方位 + 距离，模型据此行动而不是拿计数猜坐标。只读、无副作用。
 *
 * <p>实现见包内 {@link PlayerActionMechanics#findTarget}。
 */
public final class PlayerPerceptionPlugin implements AssistantPlugin {
	@Override
	public String id() {
		return "player_perception";
	}

	@Override
	public List<ToolDefinition> tools() {
		JsonObject findProps = new JsonObject();
		findProps.add("target", ToolSchema.prop("string",
				"What to find: a block/item ID (minecraft:oak_log, oak_log) or a keyword (log, chest, iron, player, monster…)."));
		findProps.add("radius", ToolSchema.prop("integer", "Search radius (default 12, max 20)."));
		return List.of(
				new ToolDefinition("player_find",
						"Find things around the assistant by keyword/ID, returning exact coordinates + bearing "
								+ "(how many blocks east/south/west/north) + distance. "
								+ "target can be a block/item ID (e.g. minecraft:oak_log, oak_log) or a plain keyword "
								+ "(e.g. \"log\", \"chest\", \"iron\", \"player\", \"monster\"). "
								+ "Before acting (mining/placing/going to an item), call player_find to get exact coordinates — don't guess coordinates.",
						ToolSchema.object(findProps, "target"),
						PlayerActionMechanics::findTarget));
	}

	@Override
	public String systemPromptFragment() {
		return """
				## Perception

				- **`player_find`** — find things by keyword/ID and return exact coordinates (always use it first to get coordinates — don't guess)

				Your own position, environment, nearby blocks and nearby entities are provided automatically in the
				**Assistant State** JSON of the system context every round — do not call tools just to re-check what the context
				already shows. Use `player_find` for a specific target beyond the nearby context.""";
	}
}
