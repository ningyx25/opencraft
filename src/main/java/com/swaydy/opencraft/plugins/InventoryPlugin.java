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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 物品插件：管理助手自己的背包与装备栏（与生存玩家一致）。
 *
 * - list_inventory：列出助手（或主人）背包物品清单 + 助手当前装备；
 * - equip：从助手背包把物品穿/拿到对应的装备栏（护甲→护甲栏，工具/武器→主手）；
 * - hand_to_player：从助手背包取出物品递给主人（背包满则掉主人脚边）；
 * - gameContextFragment：把「我的背包 + 我的装备」实时注入每轮对话的 system 上下文，
 *   模型不需要先调用工具也能知道背包里有什么（避免模型“看不到背包”瞎回答）。
 */
public class InventoryPlugin implements AssistantPlugin {
	/** 上下文里最多列出的物品种类数（防止 system 过长）。 */
	private static final int CONTEXT_MAX_ITEMS = 16;

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
				"物品 id，如 minecraft:diamond_pickaxe 或 minecraft:iron_chestplate；"
						+ "从助手背包取并穿/拿到对应的装备栏（护甲进护甲栏，其余进主手）。"));
		JsonObject handProps = new JsonObject();
		handProps.add("item", ToolSchema.prop("string", "物品 id，如 minecraft:cobblestone。"));
		handProps.add("amount", ToolSchema.prop("integer", "数量（默认 1）。"));
		return List.of(
				new ToolDefinition("list_inventory",
						"列出助手背包（或主人背包）里的物品清单（id×数量），以及助手当前穿着的装备。"
								+ "助手自己的背包内容一般已在你的上下文里（【我的背包】），"
								+ "本工具主要用于查看主人背包或刷新确认。",
						ToolSchema.object(listProps),
						this::listInventory),
				new ToolDefinition("equip",
						"把助手背包里的物品穿/拿到对应的装备栏：护甲（头盔/胸甲/护腿/靴子）穿上护甲栏，"
								+ "工具/武器拿在主手。物品不在背包则报错。",
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
		return "【物品】助手和生存玩家一样有自己的背包（36 格）和装备栏（头盔/胸甲/护腿/靴子/主手/副手），"
				+ "背包与装备内容每轮都会实时出现在你的上下文里（【我的背包】/【我的装备】），直接据此回答；"
				+ "equip 从自己背包拿工具/护甲装备上；hand_to_player 把物品递给主人；"
				+ "list_inventory 可查看主人背包或刷新清单。";
	}

	/**
	 * 每轮对话注入的实时上下文：我的背包 + 我的装备。
	 * 模型不需要先调用 list_inventory 就知道自己有什么——这是“助手看不到背包”的根治。
	 */
	@Override
	public String gameContextFragment(ToolContext ctx) {
		AiAssistantEntity assistant = ctx.assistantEntity();
		if (assistant == null) {
			return null;
		}
		return "【我的背包】" + formatBackpack(assistant)
				+ "；【我的装备】" + formatEquipment(assistant);
	}

	private ToolResult listInventory(ToolContext ctx, JsonObject args) {
		AiAssistantEntity assistant = ctx.assistantEntity();
		if (assistant == null) {
			return ToolResult.error("list_inventory 只对实体形态助手可用（玩家形态用 player_inventory）。");
		}
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
		return ToolResult.ok("助手背包: " + formatBackpack(assistant)
				+ "；装备: " + formatEquipment(assistant));
	}

	private ToolResult equip(ToolContext ctx, JsonObject args) {
		AiAssistantEntity assistant = ctx.assistantEntity();
		if (assistant == null) {
			return ToolResult.error("equip 只对实体形态助手可用（玩家形态有真实的装备栏）。");
		}
		ToolArgs a = new ToolArgs(args);
		String itemId = a.strOf("item", "");
		Holder<net.minecraft.world.item.Item> item = AiCompanionService.resolveItem(itemId);
		if (item == null) {
			return ToolResult.error("我不认识物品 \"" + itemId + "\"，请用类似 minecraft:diamond_pickaxe 的物品 ID。");
		}
		SimpleContainer inv = assistant.getInventory();
		// 从背包里找该物品
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (!stack.isEmpty() && stack.is(item)) {
				// 护甲穿到对应护甲栏（头盔/胸甲/护腿/靴子），其余拿在主手
				EquipmentSlot slot = assistant.getEquipmentSlotForItem(stack);
				ItemStack old = assistant.getItemBySlot(slot);
				if (!old.isEmpty()) {
					ItemStack left = assistant.giveToInventory(old);
					if (!left.isEmpty()) {
						// 背包已满：旧装备掉落到助手脚边，绝不凭空消失
						assistant.spawnAtLocation(ctx.level(), left);
					}
				}
				ItemStack take = inv.removeItem(i, Math.min(1, stack.getCount()));
				assistant.setItemSlot(slot, slot.limit(take));
				return ToolResult.ok("已把 " + shortName(item.value().getDescriptionId())
						+ " 装备到" + slotName(slot) + "。");
			}
		}
		return ToolResult.error("助手背包里没有 " + shortName(item.value().getDescriptionId())
				+ "，无法装备。先 look_around / 让主人给一些？");
	}

	private ToolResult handToPlayer(ToolContext ctx, JsonObject args) {
		AiAssistantEntity assistant = ctx.assistantEntity();
		if (assistant == null) {
			return ToolResult.error("hand_to_player 只对实体形态助手可用（玩家形态用 player_hand_to_player）。");
		}
		ToolArgs a = new ToolArgs(args);
		String itemId = a.strOf("item", "");
		int amount = Math.max(1, Math.min(640, a.intOf("amount", 1)));
		Holder<net.minecraft.world.item.Item> item = AiCompanionService.resolveItem(itemId);
		if (item == null) {
			return ToolResult.error("我不认识物品 \"" + itemId + "\"，请用类似 minecraft:cobblestone 的物品 ID。");
		}
		SimpleContainer inv = assistant.getInventory();
		int given = 0;
		for (int i = 0; i < inv.getContainerSize() && given < amount; i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty() || !stack.is(item)) {
				continue;
			}
			int toTake = Math.min(amount - given, stack.getCount());
			ItemStack taken = inv.removeItem(i, toTake);
			// 注意：Inventory.add 成功后会把传入的 taken 数量清零，先记下数量再计数
			int takenCount = taken.getCount();
			if (ctx.owner().getInventory().add(taken)) {
				given += takenCount;
			} else {
				// 主人背包满：掉落到主人脚边（仍然算已给出）
				ctx.owner().drop(taken, false);
				given += takenCount;
			}
		}
		if (given == 0) {
			return ToolResult.error("助手背包里没有 " + shortName(item.value().getDescriptionId())
					+ "，无法递给你。先 look_around / 让主人给一些？");
		}
		return ToolResult.ok("已把 " + shortName(item.value().getDescriptionId()) + " ×" + given + " 给你。");
	}

	/** 背包摘要：非空物品按“简称×数量”列出，种类太多时截断（避免上下文过长）。 */
	private static String formatBackpack(AiAssistantEntity assistant) {
		SimpleContainer inv = assistant.getInventory();
		int total = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (!inv.getItem(i).isEmpty()) {
				total++;
			}
		}
		if (total == 0) {
			return "空";
		}
		StringBuilder sb = new StringBuilder();
		int shown = 0;
		for (int i = 0; i < inv.getContainerSize() && shown < CONTEXT_MAX_ITEMS; i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty()) {
				continue;
			}
			if (shown > 0) {
				sb.append(", ");
			}
			sb.append(shortName(stack.getItem().getDescriptionId())).append("×")
					.append(stack.getCount());
			shown++;
		}
		if (total > shown) {
			sb.append(" 等").append(total - shown).append("种");
		}
		return sb.toString();
	}

	/** 装备摘要：手/副手/头盔/胸甲/护腿/靴子。 */
	private static String formatEquipment(AiAssistantEntity assistant) {
		StringBuilder sb = new StringBuilder();
		boolean any = false;
		EquipmentSlot[] order = {EquipmentSlot.HEAD, EquipmentSlot.CHEST,
				EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};
		for (EquipmentSlot slot : order) {
			ItemStack stack = assistant.getItemBySlot(slot);
			if (stack.isEmpty()) {
				continue;
			}
			if (any) {
				sb.append(", ");
			}
			any = true;
			sb.append(slotName(slot)).append("=")
					.append(shortName(stack.getItem().getDescriptionId()));
		}
		return any ? sb.toString() : "无";
	}

	private static String slotName(EquipmentSlot slot) {
		return switch (slot) {
			case HEAD -> "头盔";
			case CHEST -> "胸甲";
			case LEGS -> "护腿";
			case FEET -> "靴子";
			case OFFHAND -> "副手";
			default -> "主手";
		};
	}

	private static String shortName(String key) {
		if (key == null) {
			return "?";
		}
		int idx = key.lastIndexOf('.');
		return idx < 0 ? key : key.substring(idx + 1);
	}
}