package com.swaydy.opencraft.plugins;

import com.google.gson.JsonObject;
import com.swaydy.opencraft.agent.AssistantPlugin;
import com.swaydy.opencraft.agent.ToolContext;
import com.swaydy.opencraft.agent.ToolDefinition;
import com.swaydy.opencraft.agent.ToolResult;
import com.swaydy.opencraft.ai.AiCompanionService;
import com.swaydy.opencraft.entity.AiAssistantEntity;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.List;

/**
 * 合成插件：让助手用自己背包的材料合成物品（2×2 / 3×3 有序/无序配方）。
 *
 * 实现：把助手背包的物品放进一个 CraftingInput，查匹配配方，assemble 出产物，
 * 材料从背包扣除，产物进背包。无配方/材料不足返回明确错误。
 */
public class CraftingPlugin implements AssistantPlugin {
	@Override
	public String id() {
		return "crafting";
	}

	@Override
	public List<ToolDefinition> tools() {
		JsonObject props = new JsonObject();
		props.add("item", ToolSchema.prop("string",
				"要合成的物品 id，如 minecraft:diamond_sword。"));
		props.add("amount", ToolSchema.prop("integer", "合成数量（默认 1）。"));
		return List.of(new ToolDefinition("craft",
				"用助手背包里的材料合成指定物品。只支持 2×2 和 3×3 的合成配方"
						+ "（如镐、剑、木板、火把等）。材料不足或没有配方时返回错误说明缺什么。"
						+ "产物会进入助手背包（之后可用 hand_to_player 给你）。",
				ToolSchema.object(props, "item"),
				this::craft));
	}

	@Override
	public String systemPromptFragment() {
		return "【合成】你有 craft 工具用背包材料合成物品。先 list_inventory 确认材料，再 craft；"
				+ "缺材料时告诉玩家缺什么。合出来的东西先放助手背包，需要时 hand_to_player 递给玩家。";
	}

	private ToolResult craft(ToolContext ctx, JsonObject args) {
		ToolArgs a = new ToolArgs(args);
		String itemId = a.strOf("item", "");
		int amount = Math.max(1, Math.min(64, a.intOf("amount", 1)));
		Holder<net.minecraft.world.item.Item> item = AiCompanionService.resolveItem(itemId);
		if (item == null) {
			return ToolResult.error("我不认识物品 \"" + itemId + "\"，请用类似 minecraft:diamond_sword 的物品 ID。");
		}
		AiAssistantEntity assistant = ctx.assistant();
		SimpleContainer inv = assistant.getInventory();
		ServerLevel level = ctx.level();
		RegistryAccess registryAccess = level.registryAccess();

		// 尝试 3×3（用背包前 9 格）与 2×2（用前 4 格）两种网格
		CraftingInput best = null;
		RecipeHolder<CraftingRecipe> bestRecipe = null;
		for (int size : new int[]{3, 2}) {
			List<ItemStack> grid = new ArrayList<>();
			int cells = size * size;
			for (int i = 0; i < cells && i < inv.getContainerSize(); i++) {
				grid.add(inv.getItem(i).copy());
			}
			while (grid.size() < cells) {
				grid.add(ItemStack.EMPTY);
			}
			CraftingInput input = CraftingInput.of(size, size, grid);
			// 遍历全部合成配方，找能匹配且产物是目标物品的
			for (RecipeHolder<?> holder : level.recipeAccess().getRecipes()) {
				if (!(holder.value() instanceof CraftingRecipe recipe)) {
					continue;
				}
				if (!recipe.matches(input, level)) {
					continue;
				}
				ItemStack result = recipe.assemble(input, registryAccess);
				if (!result.isEmpty() && result.is(item)) {
					best = input;
					@SuppressWarnings("unchecked")
					RecipeHolder<CraftingRecipe> typed = (RecipeHolder<CraftingRecipe>) holder;
					bestRecipe = typed;
					break;
				}
			}
			if (bestRecipe != null) {
				break;
			}
		}
		if (bestRecipe == null) {
			// 找“缺什么材料”：取一个产物是目标物品的配方，列出其输入材料
			return ToolResult.error("用我背包里的材料无法合成 "
					+ shortName(item.value().getDescriptionId())
					+ "。可能是材料不足、没有配方，或配方需要更具体的材料。"
					+ "请先 list_inventory 看看有什么，或告诉我你需要什么材料。");
		}

		// 扣除材料：把网格里用到的物品从背包移除（按数量）
		for (int i = 0; i < best.size(); i++) {
			ItemStack used = best.getItem(i);
			if (used.isEmpty()) {
				continue;
			}
			int toRemove = used.getCount();
			// 从背包里按匹配扣除
			for (int s = 0; s < inv.getContainerSize() && toRemove > 0; s++) {
				ItemStack slot = inv.getItem(s);
				if (slot.isEmpty() || !slot.is(used.getItem())) {
					continue;
				}
				int take = Math.min(toRemove, slot.getCount());
				slot.shrink(take);
				toRemove -= take;
			}
		}
		// 产物入背包
		ItemStack result = bestRecipe.value().assemble(best, registryAccess);
		int perCraft = result.getCount();
		ItemStack crafted = result.copy();
		crafted.setCount(Math.min(perCraft * amount, 64));
		ItemStack remaining = assistant.giveToInventory(crafted);
		if (!remaining.isEmpty()) {
			// 背包放不下：掉落在助手脚边
			level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(level,
					assistant.getX(), assistant.getY() + 1.0, assistant.getZ(), remaining));
		}
		return ToolResult.ok("已合成 " + shortName(item.value().getDescriptionId()) + " ×"
				+ crafted.getCount() + "（放进助手背包）。");
	}

	private static String shortName(String key) {
		if (key == null) {
			return "?";
		}
		int idx = key.lastIndexOf('.');
		return idx < 0 ? key : key.substring(idx + 1);
	}
}