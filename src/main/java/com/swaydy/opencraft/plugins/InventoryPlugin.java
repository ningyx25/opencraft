package com.swaydy.opencraft.plugins;

import com.google.gson.JsonObject;
import com.swaydy.opencraft.agent.AssistantPlugin;
import com.swaydy.opencraft.agent.ToolContext;
import com.swaydy.opencraft.agent.ToolDefinition;
import com.swaydy.opencraft.agent.ToolResult;
import com.swaydy.opencraft.ai.AiCompanionService;
import com.swaydy.opencraft.entity.AiAssistantEntity;
import net.minecraft.core.Holder;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * 物品插件：管理助手自己的背包。
 *
 * - list_inventory：列出助手（或主人）背包物品清单；
 * - equip：从助手背包把物品换到主手（挖掘前拿镐）；
 * - hand_to_player：从助手背包取出物品递给主人（背包满则掉主人脚边）。
 */
public class InventoryPlugin implements AssistantPlugin {
	@Override
	public String id() {
		return "inventory";
	}

	@Override
	public List<ToolDefinition> tools() {
		JsonObject listProps = new JsonObject();
		listProps.add("whose", ToolSchema.prop("string",
				"查看谁的背包：\"self\"（助手自己，默认）或 \"player\"（主人）。"));
		JsonObject equipProps = new JsonObject();
		equipProps.add("item", ToolSchema.prop("string",
				"物品 id，如 minecraft:diamond_pickaxe；从助手背包取并放到主手。"));
		JsonObject handProps = new JsonObject();
		handProps.add("item", ToolSchema.prop("string", "物品 id，如 minecraft:cobblestone。"));
		handProps.add("amount", ToolSchema.prop("integer", "数量（默认 1）。"));
		return List.of(
				new ToolDefinition("list_inventory",
						"列出助手背包（或主人背包）里的物品清单（id×数量）。行动前先看自己有什么材料/工具。",
						ToolSchema.object(listProps),
						this::listInventory),
				new ToolDefinition("equip",
						"把助手背包里的物品换到主手（如挖掘前拿镐、战斗前拿剑）。物品不在背包则报错。",
						ToolSchema.object(equipProps, "item"),
						this::equip),
				new ToolDefinition("hand_to_player",
						"从助手背包取出物品递给主人（进主人背包；主人背包满则掉落到主人脚边）。"
								+ "只能给助手自己有的物品。",
						ToolSchema.object(handProps, "item"),
						this::handToPlayer));
	}

	@Override
	public String systemPromptFragment() {
		return "【物品】助手有自己的背包（9 格）。list_inventory 查看自己/主人的物品；"
				+ "equip 从自己背包拿工具到主手；hand_to_player 把物品递给主人。"
				+ "给主人东西前先 list_inventory 确认自己有。";
	}

	private ToolResult listInventory(ToolContext ctx, JsonObject args) {
		ToolArgs a = new ToolArgs(args);
		String whose = a.strOf("whose", "self").toLowerCase(java.util.Locale.ROOT);
		if (whose.equals("player")) {
			StringBuilder sb = new StringBuilder("主人背包: ");
			boolean any = false;
			for (ItemStack stack : ctx.owner().getInventory().getNonEquipmentItems()) {
				if (stack.isEmpty()) {
					continue;
				}
				any = true;
				sb.append(shortName(stack.getItem().getDescriptionId())).append("×")
						.append(stack.getCount()).append(" ");
			}
			return ToolResult.ok(any ? sb.toString() : "主人背包是空的。");
		}
		SimpleContainer inv = ctx.assistant().getInventory();
		StringBuilder sb = new StringBuilder("助手背包: ");
		boolean any = false;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty()) {
				continue;
			}
			any = true;
			sb.append(shortName(stack.getItem().getDescriptionId())).append("×")
					.append(stack.getCount()).append(" ");
		}
		return ToolResult.ok(any ? sb.toString() : "助手背包是空的。");
	}

	private ToolResult equip(ToolContext ctx, JsonObject args) {
		ToolArgs a = new ToolArgs(args);
		String itemId = a.strOf("item", "");
		Holder<net.minecraft.world.item.Item> item = AiCompanionService.resolveItem(itemId);
		if (item == null) {
			return ToolResult.error("我不认识物品 \"" + itemId + "\"，请用类似 minecraft:diamond_pickaxe 的物品 ID。");
		}
		AiAssistantEntity assistant = ctx.assistant();
		SimpleContainer inv = assistant.getInventory();
		// 从背包里找该物品
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (!stack.isEmpty() && stack.is(item)) {
				// 换到主手：把主手物品放回背包，再放上目标物品
				ItemStack mainHand = assistant.getMainHandItem();
				if (!mainHand.isEmpty()) {
					assistant.giveToInventory(mainHand);
				}
				ItemStack take = inv.removeItem(i, Math.min(1, stack.getCount()));
				assistant.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, take);
				return ToolResult.ok("已把 " + shortName(item.value().getDescriptionId()) + " 拿在手上。");
			}
		}
		return ToolResult.error("助手背包里没有 " + shortName(item.value().getDescriptionId())
				+ "，无法装备。先 look_around / 让主人给一些？");
	}

	private ToolResult handToPlayer(ToolContext ctx, JsonObject args) {
		ToolArgs a = new ToolArgs(args);
		String itemId = a.strOf("item", "");
		int amount = Math.max(1, Math.min(640, a.intOf("amount", 1)));
		Holder<net.minecraft.world.item.Item> item = AiCompanionService.resolveItem(itemId);
		if (item == null) {
			return ToolResult.error("我不认识物品 \"" + itemId + "\"，请用类似 minecraft:cobblestone 的物品 ID。");
		}
		AiAssistantEntity assistant = ctx.assistant();
		SimpleContainer inv = assistant.getInventory();
		int given = 0;
		for (int i = 0; i < inv.getContainerSize() && given < amount; i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty() || !stack.is(item)) {
				continue;
			}
			int toTake = Math.min(amount - given, stack.getCount());
			ItemStack taken = inv.removeItem(i, toTake);
			if (ctx.owner().getInventory().add(taken)) {
				given += taken.getCount();
			} else {
				// 主人背包满：掉落到主人脚边（仍然算已给出）
				ctx.owner().drop(taken, false);
				given += taken.getCount();
			}
		}
		if (given == 0) {
			return ToolResult.error("助手背包里没有 " + shortName(item.value().getDescriptionId())
					+ "，无法递给你。先 look_around / 让主人给一些？");
		}
		return ToolResult.ok("已把 " + shortName(item.value().getDescriptionId()) + " ×" + given + " 给你。");
	}

	private static String shortName(String key) {
		if (key == null) {
			return "?";
		}
		int idx = key.lastIndexOf('.');
		return idx < 0 ? key : key.substring(idx + 1);
	}
}