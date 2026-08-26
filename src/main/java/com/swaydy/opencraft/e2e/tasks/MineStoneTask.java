package com.swaydy.opencraft.e2e.tasks;

import com.swaydy.opencraft.e2e.E2EContext;
import com.swaydy.opencraft.e2e.E2ETask;

/**
 * 内置 e2e 任务「mine_stone」：在平台上挖一些石头。
 *
 * <p>场景：harness 铺好的石质平台就是原料（石头 → 木镐挖掘 → 圆石）；setup 给助手一把木镐
 * （空手挖石头不掉落圆石，本任务只测"挖石头收集"，不测"做工具"）。
 * 验证：助手背包里有 {@code cobblestone}（玩家式挖掘流程掉落的圆石已入包，或递给了主人）。</p>
 */
public class MineStoneTask implements E2ETask {

	@Override
	public String id() {
		return "mine_stone";
	}

	@Override
	public String description() {
		return "在平台上挖一些石头";
	}

	@Override
	public String taskPrompt() {
		return "直接开始执行，不要向我确认或提问。你背包里有一把木镐，用它挖 1 块平台表面的石头即可，把掉落的圆石捡起来后立刻汇报完成（不要多挖）。";
	}

	@Override
	public long timeoutMillis() {
		return 4 * 60_000L;
	}

	@Override
	public void setup(E2EContext ctx) {
		// 给助手一把木镐：空手挖石头不掉落圆石（原版机制），辅助任务只测"挖石头收集"而非"做工具"
		net.minecraft.world.item.ItemStack pickaxe = new net.minecraft.world.item.ItemStack(
				net.minecraft.world.item.Items.WOODEN_PICKAXE);
		ctx.assistant().getInventory().add(pickaxe);
	}

	@Override
	public boolean verify(E2EContext ctx) {
		if (ctx.countInInventory("minecraft:cobblestone") >= 1
				|| ctx.countInOwnerInventory("minecraft:cobblestone") >= 1) {
			return true;
		}
		// 兜底：圆石还没入包但已作为掉落物在平台附近（拾取保护期/背包满等），也算挖到了
		return cobblestoneOnGround(ctx) > 0;
	}

	/** 测试区平台上的圆石掉落物数量。 */
	private static int cobblestoneOnGround(E2EContext ctx) {
		net.minecraft.core.BlockPos o = ctx.areaOrigin();
		net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
				o.getX() - com.swaydy.opencraft.e2e.E2EHarness.platformRadius() - 2, o.getY() - 3,
				o.getZ() - com.swaydy.opencraft.e2e.E2EHarness.platformRadius() - 2,
				o.getX() + com.swaydy.opencraft.e2e.E2EHarness.platformRadius() + 2, o.getY() + 12,
				o.getZ() + com.swaydy.opencraft.e2e.E2EHarness.platformRadius() + 2);
		net.minecraft.world.item.Item item = resolveItem(ctx, "minecraft:cobblestone");
		if (item == null) {
			return 0;
		}
		int count = 0;
		for (net.minecraft.world.entity.item.ItemEntity entity
				: ctx.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box)) {
			net.minecraft.world.item.ItemStack stack = entity.getItem();
			if (!stack.isEmpty() && stack.getItem() == item) {
				count += stack.getCount();
			}
		}
		return count;
	}

	private static net.minecraft.world.item.Item resolveItem(E2EContext ctx, String itemId) {
		var holder = com.swaydy.opencraft.ai.AiCompanionService.resolveItem(itemId);
		return holder == null ? null : holder.value();
	}
}
