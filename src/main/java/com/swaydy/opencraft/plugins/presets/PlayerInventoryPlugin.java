package com.swaydy.opencraft.plugins.presets;

import com.google.gson.JsonObject;
import com.swaydy.opencraft.plugins.ToolDefinition;
import com.swaydy.opencraft.plugins.ToolSchema;

import java.util.List;

/**
 * 背包/物品能力族插件：查看完整背包（{@code player_inventory}，只读）、槽位交换/装备/丢弃
 * （{@code player_item_move}）、热键栏选择（{@code player_hotbar_select}）、递物品给主人
 * （{@code player_hand_to_player}）。实现见包内 {@link PlayerActionMechanics}。
 */
public final class PlayerInventoryPlugin implements AssistantPlugin {
	@Override
	public String id() {
		return "player_inventory";
	}

	@Override
	public List<ToolDefinition> tools() {
		JsonObject listProps = new JsonObject();
		listProps.add("whose", ToolSchema.prop("string",
				"Whose inventory to list: \"self\" (default, the assistant's own inventory) or \"owner\"/\"player\" (the owner's inventory)."));
		JsonObject moveProps = new JsonObject();
		moveProps.add("from", ToolSchema.prop("string",
				"Source slot: a number 0–35 (main inventory) or a name: mainhand (= currently selected hotbar slot), offhand, helmet, chestplate, leggings, boots."));
		moveProps.add("to", ToolSchema.prop("string",
				"Destination slot: same format as from, or -1 to drop the item on the ground (like pressing Q). The two slots swap contents."));
		JsonObject hotbarProps = new JsonObject();
		hotbarProps.add("slot", ToolSchema.prop("integer",
				"Hotbar slot to select as the main hand (0–8, where 0 is the leftmost slot)."));
		JsonObject handProps = new JsonObject();
		handProps.add("item", ToolSchema.prop("string", "Item id, e.g. minecraft:cobblestone."));
		handProps.add("amount", ToolSchema.prop("integer", "Amount (default 1)."));
		return List.of(
				new ToolDefinition("player_inventory",
						"List the full contents of the assistant's own player inventory (or the owner's) in detail: "
								+ "every non-empty slot with its slot number (0–35, matching player_item_move), the currently selected "
								+ "main-hand hotbar slot, durability of tools/armor, and equipment (helmet/chestplate/leggings/boots/offhand). "
								+ "Call this when you need an exact, complete inventory view — e.g. before planning a craft, "
								+ "when deciding what to hand to the owner, or when the inventory summary in the Assistant State "
								+ "context is truncated and you must know exactly what you have. Note: player_inventory is read-only "
								+ "and never changes anything.",
						ToolSchema.object(listProps),
						PlayerActionMechanics::listInventory),
				new ToolDefinition("player_item_move",
						"Move (swap) items between any two slots in the assistant's inventory. "
								+ "Slots: numbers 0–35 for main inventory, or named slots: "
								+ "mainhand (= currently selected hotbar slot), offhand, helmet, chestplate, leggings, boots. "
								+ "The two slots swap contents — use this to equip armor/weapons, unequip them, or rearrange items.",
						ToolSchema.object(moveProps, "from", "to"),
						PlayerActionMechanics::itemMove),
				new ToolDefinition("player_hotbar_select",
						"Select which hotbar slot (0–8) is the main hand. "
								+ "Slot 0 is leftmost, slot 8 is rightmost. "
								+ "Use player_item_move to put items into hotbar slots first, then select the slot here.",
						ToolSchema.object(hotbarProps, "slot"),
						PlayerActionMechanics::hotbarSelect),
				new ToolDefinition("player_hand_to_player",
						"Take an item out of the assistant's inventory and hand it to the owner (goes into the owner's inventory; "
								+ "drops at the owner's feet if their inventory is full).",
						ToolSchema.object(handProps, "item"),
						PlayerActionMechanics::handToPlayer));
	}

	@Override
	public String systemPromptFragment() {
		return """
				## Inventory & Items

				- **`player_item_move`** — swap items between any two slots (slots 0–35 = main inventory, named slots: `mainhand`/`offhand`/`helmet`/`chestplate`/`leggings`/`boots`; `-1` as destination drops the item like pressing Q)
				- **`player_hotbar_select`** — pick which hotbar slot (0–8) is the main hand
				- **`player_hand_to_player`** — hand an item to the owner
				- **`player_inventory`** — list the FULL inventory of the assistant (or the owner) in detail: every non-empty slot with
				  its slot number, the selected main-hand slot, durability, and equipment — call it when you need an exact,
				  complete inventory view (planning crafts, deciding what to hand over, or the context summary is not enough)

				The Assistant State inventory summary is capped/abridged: when you need the exact, complete inventory
				(which slot holds what, durability, every stack) call `player_inventory` (read-only, safe).""";
	}
}
