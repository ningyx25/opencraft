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
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 合成插件：让助手用自己背包的材料合成物品，**规则与普通玩家完全一致**：
 * - 2×2 及更小的配方（木棍、圆石块、按钮等）——助手随身合成栏随时可合成；
 * - 3×3 配方（镐、钻石块等）——必须助手附近有工作台（crafting table）才能合成；
 * - 材料从【整个背包】搜索（不再要求材料在前 9 格）。
 *
 * 实现：遍历所有普通配方（跳过 special），先按产物预过滤；对产物匹配的配方，
 * 按配方形状（shaped 用精确 w×h，shapeless 用集合匹配）从背包任意槽位凑出网格，
 * 凑齐后用 vanilla 的 matches 验证，命中则按套数扣料并合成。
 */
public class CraftingPlugin implements AssistantPlugin {
	/** 用于产物预过滤的空输入：普通配方的 assemble 只返回 result，不读输入。 */
	private static final CraftingInput EMPTY_INPUT = CraftingInput.of(1, 1, List.of(ItemStack.EMPTY));

	@Override
	public String id() {
		return "crafting";
	}

	@Override
	public List<ToolDefinition> tools() {
		JsonObject props = new JsonObject();
		props.add("item", ToolSchema.prop("string",
				"要合成的物品 id，如 minecraft:diamond_block。"));
		props.add("amount", ToolSchema.prop("integer", "合成数量（默认 1，材料不够时能合成几套就合成几套）。"));
		return List.of(new ToolDefinition("craft",
				"用助手背包里的材料合成指定物品。会自动从整个背包搜索材料（不需要材料排在背包前面）。"
						+ "规则与普通玩家一致：2×2 及更小的配方（如木棍、圆石块）随时可以合成；"
						+ "3×3 配方（如镐、钻石块）需要助手附近有工作台——"
						+ "如果报错“需要工作台”，先用 goto 走到工作台旁边再 craft。"
						+ "材料不足或没有配方时返回错误说明。"
						+ "产物会进入助手背包（之后可用 hand_to_player 给你）。",
				ToolSchema.object(props, "item"),
				this::craft));
	}

	@Override
	public String systemPromptFragment() {
		return "【合成】你有 craft 工具用背包材料合成物品（自动从整个背包取料）。"
				+ "规则与玩家一致：2×2 及更小的配方随时可合成；3×3 配方（如镐、钻石块）需要附近有工作台——"
				+ "报错“需要工作台”时先用 goto 走到工作台旁再 craft。"
				+ "合出来的东西先放助手背包，需要时 hand_to_player 递给玩家。";
	}

	private ToolResult craft(ToolContext ctx, JsonObject args) {
		AiAssistantEntity assistant = ctx.assistantEntity();
		if (assistant == null) {
			return ToolResult.error("craft 只对实体形态助手可用（玩家形态用 player_craft）。");
		}
		ToolArgs a = new ToolArgs(args);
		String itemId = a.strOf("item", "");
		int amount = Math.max(1, Math.min(64, a.intOf("amount", 1)));
		Holder<net.minecraft.world.item.Item> item = AiCompanionService.resolveItem(itemId);
		if (item == null) {
			return ToolResult.error("我不认识物品 \"" + itemId + "\"，请用类似 minecraft:diamond_block 的物品 ID。");
		}
		SimpleContainer inv = assistant.getInventory();
		ServerLevel level = ctx.level();
		RegistryAccess registryAccess = level.registryAccess();

		// 玩家式规则：2×2 随身合成栏随时可用；3×3 需要附近有工作台
		boolean hasWorkbench = hasWorkbenchNearby(assistant);
		boolean sawWorkbenchRecipe = false;

		// 遍历全部普通合成配方：先按“产物类型”预过滤，再尝试从整个背包凑出网格
		for (RecipeHolder<?> holder : level.recipeAccess().getRecipes()) {
			if (!(holder.value() instanceof CraftingRecipe recipe) || recipe.isSpecial()) {
				continue; // 跳过特殊配方（烟花/旗帜染色等，assemble 依赖输入内容）
			}
			ItemStack result = recipe.assemble(EMPTY_INPUT, registryAccess);
			if (result.isEmpty() || !result.is(item)) {
				continue; // 产物不是目标物品
			}
			if (needsWorkbench(recipe)) {
				if (!hasWorkbench) {
					sawWorkbenchRecipe = true;
					continue; // 3×3 配方但没有工作台：先记下来，最后给明确提示
				}
			}
			GridMatch match = tryFillGrid(recipe, inv);
			if (match == null) {
				continue; // 背包材料凑不出这个配方的形状
			}
			CraftingInput input = CraftingInput.of(match.width, match.height, match.grid);
			if (!recipe.matches(input, level)) {
				continue;
			}
			int perCraft = recipe.assemble(input, registryAccess).getCount();
			int sets = match.maxSets(amount, inv);
			match.consume(inv, sets);
			ItemStack crafted = recipe.assemble(input, registryAccess).copy();
			crafted.setCount(Math.min(perCraft * sets, crafted.getMaxStackSize()));
			ItemStack remaining = assistant.giveToInventory(crafted);
			if (!remaining.isEmpty()) {
				// 背包放不下：掉落在助手脚边
				level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(level,
						assistant.getX(), assistant.getY() + 1.0, assistant.getZ(), remaining));
			}
			return ToolResult.ok("已合成 " + shortName(item.value().getDescriptionId()) + " ×"
					+ crafted.getCount() + "（放进助手背包）"
					+ (sets < amount ? "。材料只够合成 " + sets + " 套" : "") + "。");
		}
		if (sawWorkbenchRecipe) {
			return ToolResult.error("合成 " + shortName(item.value().getDescriptionId())
					+ " 需要工作台（3×3 合成格，和玩家一样）。"
					+ "请先让我走到工作台旁边（或把工作台放在我附近），再试一次。");
		}
		return ToolResult.error("用我背包里的材料无法合成 "
				+ shortName(item.value().getDescriptionId())
				+ "。可能是材料不足或没有配方。请先 list_inventory 看看有什么，或告诉我你需要什么材料。");
	}

	/**
	 * 该配方是否需要工作台：玩家随身 2×2 合成栏放不下的配方
	 * （shaped 任一维度 > 2，或 shapeless 材料数 > 4）。
	 */
	private static boolean needsWorkbench(CraftingRecipe recipe) {
		if (recipe instanceof ShapedRecipe shaped) {
			return shaped.getWidth() > 2 || shaped.getHeight() > 2;
		}
		if (recipe instanceof ShapelessRecipe shapeless) {
			return recipe.placementInfo().ingredients().size() > 4;
		}
		return false;
	}

	/** 助手附近（水平 ±5、垂直 ±3）是否有工作台方块。 */
	private static boolean hasWorkbenchNearby(AiAssistantEntity assistant) {
		if (!(assistant.level() instanceof ServerLevel level)) {
			return false;
		}
		net.minecraft.core.BlockPos center = assistant.blockPosition();
		for (int dx = -5; dx <= 5; dx++) {
			for (int dy = -3; dy <= 3; dy++) {
				for (int dz = -5; dz <= 5; dz++) {
					if (level.getBlockState(center.offset(dx, dy, dz))
							.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * 从【整个背包】按配方形状取料填网格（不改动背包，失败返回 null）：
	 * - shaped 配方：材料按配方行优先位置放入 w×h 网格（左上角对齐，空位留空）；
	 * - shapeless 配方：位置无关，材料顺序放入网格（集合匹配）。
	 * 同一槽位的栈可被多个格子复用（如 9 个钻石在同一格），按用量虚拟计数。
	 */
	private static GridMatch tryFillGrid(CraftingRecipe recipe, SimpleContainer inv) {
		if (recipe instanceof ShapedRecipe shaped) {
			int w = shaped.getWidth();
			int h = shaped.getHeight();
			List<Ingredient> ingredients = recipe.placementInfo().ingredients(); // w*h 个，行优先
			return fill(w, h, ingredients, inv);
		}
		if (recipe instanceof ShapelessRecipe shapeless) {
			List<Ingredient> ingredients = recipe.placementInfo().ingredients(); // n 个材料
			return fill(ingredients.size(), 1, ingredients, inv);
		}
		return null;
	}

	private static GridMatch fill(int w, int h, List<Ingredient> ingredients,
	                              SimpleContainer inv) {
		int cells = w * h;
		if (ingredients.size() < cells) {
			return null;
		}
		List<ItemStack> grid = new ArrayList<>(cells);
		for (int i = 0; i < cells; i++) {
			grid.add(ItemStack.EMPTY);
		}
		int[] slotPerCell = new int[cells];
		Arrays.fill(slotPerCell, -1);
		int[] usedPerSlot = new int[inv.getContainerSize()];
		for (int c = 0; c < cells; c++) {
			Ingredient ingredient = ingredients.get(c);
			if (ingredient.isEmpty()) {
				continue;
			}
			int slot = findSlot(ingredient, inv, usedPerSlot);
			if (slot < 0) {
				return null; // 该格材料在背包里找不到
			}
			usedPerSlot[slot]++;
			grid.set(c, inv.getItem(slot).copyWithCount(1));
			slotPerCell[c] = slot;
		}
		return new GridMatch(grid, slotPerCell, w, h);
	}

	/** 从背包找一个匹配该材料且尚未用尽的槽位（0 号开始找）。 */
	private static int findSlot(Ingredient ingredient, SimpleContainer inv, int[] usedPerSlot) {
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty() || usedPerSlot[i] >= stack.getCount()) {
				continue;
			}
			if (ingredient.test(stack)) {
				return i;
			}
		}
		return -1;
	}

	/** 一次成功的网格匹配：网格内容 + 每个格子对应的背包槽位。 */
	private static final class GridMatch {
		final List<ItemStack> grid;
		final int[] slotPerCell;
		final int width;
		final int height;

		GridMatch(List<ItemStack> grid, int[] slotPerCell, int width, int height) {
			this.grid = grid;
			this.slotPerCell = slotPerCell;
			this.width = width;
			this.height = height;
		}

		/** 最多能合成几套（按每个槽位每套的用量折算，且不超过请求的 amount）。 */
		int maxSets(int amount, SimpleContainer inv) {
			int[] usagePerSlot = new int[inv.getContainerSize()];
			for (int slot : slotPerCell) {
				if (slot >= 0) {
					usagePerSlot[slot]++;
				}
			}
			int sets = amount;
			for (int slot = 0; slot < inv.getContainerSize(); slot++) {
				if (usagePerSlot[slot] > 0) {
					sets = Math.min(sets, inv.getItem(slot).getCount() / usagePerSlot[slot]);
				}
			}
			return Math.max(1, sets);
		}

		/** 按套数从背包扣除材料（同一槽位出现多次会分别扣减，等价每套扣该槽总用量）。 */
		void consume(SimpleContainer inv, int sets) {
			for (int slot : slotPerCell) {
				if (slot >= 0) {
					inv.removeItem(slot, sets);
				}
			}
		}
	}

	private static String shortName(String key) {
		if (key == null) {
			return "?";
		}
		int idx = key.lastIndexOf('.');
		return idx < 0 ? key : key.substring(idx + 1);
	}
}