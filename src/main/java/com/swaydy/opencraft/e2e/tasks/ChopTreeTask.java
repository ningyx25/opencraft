package com.swaydy.opencraft.e2e.tasks;

import com.swaydy.opencraft.e2e.E2EContext;
import com.swaydy.opencraft.e2e.E2ETask;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * 内置 e2e 任务「chop_tree」：砍一棵树。
 *
 * <p>场景：平台上种一棵 4 格橡树。验证：树干全部被破坏（区域无 {@code oak_log}
 * 站立方块），且原木没丢——要么在助手背包里（≥3），要么作为掉落物还在区域地上。</p>
 */
public class ChopTreeTask implements E2ETask {

	@Override
	public String id() {
		return "chop_tree";
	}

	@Override
	public String description() {
		return "砍一棵树并把原木收集起来";
	}

	@Override
	public String taskPrompt() {
		return "直接开始执行，不要向我确认或提问。请砍倒旁边那棵橡树，并把掉落的原木捡起来（用 player_find 找到树，走过去用 player_mine 挖）。";
	}

	@Override
	public void setup(E2EContext ctx) {
		TaskScenes.plantTree(ctx);
	}

	@Override
	public boolean verify(E2EContext ctx) {
		BlockPos base = TaskScenes.treeBase(ctx);
		int logsStanding = ctx.countBlockInRegion("minecraft:oak_log", base, 3);
		if (logsStanding > 0) {
			return false; // 树还没砍完（区域仍有站立树干）
		}
		int logsInInventory = ctx.countInInventory("minecraft:oak_log");
		int logsWithOwner = ctx.countInOwnerInventory("minecraft:oak_log");
		if (logsInInventory + logsWithOwner >= 3) {
			return true; // 原木已入包（可能被助手递给主人了）
		}
		// 原木可能还在掉落（背包满/没来得及捡）：地上有原木也算没丢
		return logsOnGround(ctx, base) > 0;
	}

	/** 树基周围地上的橡树原木掉落物数量。 */
	private static int logsOnGround(E2EContext ctx, BlockPos base) {
		ServerLevel level = ctx.level();
		Item logItem = item(ctx, "minecraft:oak_log");
		if (logItem == null) {
			return 0;
		}
		AABB box = new AABB(base.getX() - 4, base.getY() - 2, base.getZ() - 4,
				base.getX() + 4, base.getY() + 6, base.getZ() + 4);
		int count = 0;
		for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, box)) {
			ItemStack stack = entity.getItem();
			if (!stack.isEmpty() && stack.getItem() == logItem) {
				count += stack.getCount();
			}
		}
		return count;
	}

	private static Item item(E2EContext ctx, String itemId) {
		var holder = com.swaydy.opencraft.ai.AiCompanionService.resolveItem(itemId);
		return holder == null ? null : holder.value();
	}
}
