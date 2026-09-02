package com.swaydy.opencraft.plugins.presets;

import com.google.gson.JsonObject;
import com.swaydy.opencraft.plugins.ToolDefinition;
import com.swaydy.opencraft.plugins.ToolSchema;

import java.util.List;

/**
 * 容器交互能力族插件：像真实玩家一样右键打开箱子/桶/潜影盒/熔炉等容器（{@code player_container_open}）、
 * 查看两侧内容（{@code list}，只读）、shift-click 整栈取/放（{@code take/put}）、关闭（{@code close}）。
 * 实现见包内 {@link PlayerActionMechanics}。
 */
public final class PlayerContainerPlugin implements AssistantPlugin {
	@Override
	public String id() {
		return "player_container";
	}

	@Override
	public List<ToolDefinition> tools() {
		JsonObject openProps = new JsonObject();
		openProps.add("x", ToolSchema.prop("integer", "X coordinate of the container block (chest/barrel/furnace/etc.)"));
		openProps.add("y", ToolSchema.prop("integer", "Y coordinate of the container block"));
		openProps.add("z", ToolSchema.prop("integer", "Z coordinate of the container block"));
		JsonObject takePutProps = new JsonObject();
		takePutProps.add("item", ToolSchema.prop("string", "Item id, e.g. minecraft:oak_planks."));
		takePutProps.add("amount", ToolSchema.prop("integer", "Amount to take/put (default = all matching stacks)."));
		return List.of(
				new ToolDefinition("player_container_open",
						"Open a container block (chest, barrel, shulker box, furnace, hopper, dispenser, ender chest, crafting "
								+ "table…) at the given coordinates, exactly like a player right-clicking it. "
								+ "If far away, the assistant walks over first and the outcome arrives automatically as an [Event] "
								+ "message. After opening, use player_container_list to see the contents and "
								+ "player_container_take / player_container_put to move items; close with player_container_close.",
						ToolSchema.object(openProps, "x", "y", "z"),
						PlayerActionMechanics::containerOpen),
				new ToolDefinition("player_container_list",
						"List the contents of the container the assistant currently has open (its slots) together with the "
								+ "assistant's own inventory side by side, like the container GUI shows. Read-only. "
								+ "Call this after player_container_open and whenever you need to know exactly what is inside "
								+ "the container before taking or putting items.",
						ToolSchema.object(new JsonObject()),
						PlayerActionMechanics::containerList),
				new ToolDefinition("player_container_take",
						"Take an item out of the open container into the assistant's inventory by shift-clicking matching "
								+ "container slots (whole stacks at a time, exactly like a player). Requires an open container "
								+ "(player_container_open first). amount caps how many to take (may overshoot by one stack).",
						ToolSchema.object(takePutProps, "item"),
						PlayerActionMechanics::containerTake),
				new ToolDefinition("player_container_put",
						"Put an item from the assistant's inventory into the open container by shift-clicking matching "
								+ "inventory slots (whole stacks at a time, exactly like a player). Requires an open container "
								+ "(player_container_open first). amount caps how many to put (may overshoot by one stack).",
						ToolSchema.object(takePutProps, "item"),
						PlayerActionMechanics::containerPut),
				new ToolDefinition("player_container_close",
						"Close the container the assistant currently has open (like pressing Esc). No-op if nothing is open.",
						ToolSchema.object(new JsonObject()),
						PlayerActionMechanics::containerClose));
	}

	@Override
	public String systemPromptFragment() {
		return """
				## Containers

				- **`player_container_open` / `player_container_close`** — open/close a container block (chest, barrel, shulker box,
				  furnace…) like a player right-clicking it; opening may be asynchronous (walking) — the [Event] outcome arrives by itself
				- **`player_container_list`** — see the open container's contents and your inventory side by side (read-only)
				- **`player_container_take` / `player_container_put`** — shift-click whole stacks of an item between the open
				  container and your inventory (e.g. take all oak_planks from the chest, put cobblestone into the barrel)

				Containers (chests/barrels/shulker boxes/furnaces…) hold items and `player_mine` refuses to break them. To get
				items from one or store items in one: `player_find` the container → `player_container_open` it → `player_container_list`
				to see what's inside → `player_container_take` / `player_container_put` to move items → `player_container_close` when done.""";
	}
}
