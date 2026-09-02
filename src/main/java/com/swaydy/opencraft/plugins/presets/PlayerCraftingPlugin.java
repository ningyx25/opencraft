package com.swaydy.opencraft.plugins.presets;

import com.google.gson.JsonObject;
import com.swaydy.opencraft.plugins.ToolDefinition;
import com.swaydy.opencraft.plugins.ToolSchema;

import java.util.List;

/**
 * 合成能力族插件：{@code player_craft}——用助手背包材料按原版配方书流程合成
 * （随身 2×2 随时可合，3×3 需旁边真有工作台）。实现见包内 {@link PlayerActionMechanics#craft}。
 */
public final class PlayerCraftingPlugin implements AssistantPlugin {
	@Override
	public String id() {
		return "player_crafting";
	}

	@Override
	public List<ToolDefinition> tools() {
		JsonObject craftProps = new JsonObject();
		craftProps.add("item", ToolSchema.prop("string", "Item id to craft, e.g. minecraft:diamond_block."));
		craftProps.add("amount", ToolSchema.prop("integer", "Amount to craft (default 1)."));
		return List.of(
				new ToolDefinition("player_craft",
						"Have the assistant craft the given item using materials from its own player inventory (exactly like a player: "
								+ "2×2 and smaller recipes can be crafted anytime, 3×3 recipes need a nearby crafting table). "
								+ "Products go into the assistant's inventory; later you can hand them to the owner with player_hand_to_player.",
						ToolSchema.object(craftProps, "item"),
						PlayerActionMechanics::craft));
	}

	@Override
	public String systemPromptFragment() {
		return """
				## Crafting

				- **`player_craft`** — craft from inventory materials (same rules as a player; 3×3 needs a crafting table)""";
	}
}
