package com.swaydy.opencraft.e2e.tasks;

import com.swaydy.opencraft.e2e.E2EContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * e2e 任务共用的场景搭建工具：在 harness 铺好的石质平台上种/清一棵橡树。
 *
 * <p>树 = 4 格 {@code OAK_LOG} 树干 + 顶部 5 格 {@code OAK_LEAVES} 树冠，
 * 种在测试区原点东侧 3 格处（{@link #treeBase(E2EContext)}），对助手来说是一棵
 * 真实可砍的树（玩家式挖掘会掉原木）。树冠/树干会随下一任务的平台重建被清掉。</p>
 */
final class TaskScenes {
	private TaskScenes() {
	}

	/** 树干底部（种在平台上，y = 平台顶 + 1）。 */
	static BlockPos treeBase(E2EContext ctx) {
		return new BlockPos(ctx.areaOrigin().getX() + 3, ctx.areaOrigin().getY() + 1, ctx.areaOrigin().getZ());
	}

	/** 种一棵 4 格高的橡树（服务端线程）。 */
	static void plantTree(E2EContext ctx) {
		ServerLevel level = ctx.level();
		BlockPos base = treeBase(ctx);
		for (int dy = 0; dy < 4; dy++) {
			level.setBlock(base.offset(0, dy, 0), Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
		}
		level.setBlock(base.offset(0, 4, 0), Blocks.OAK_LEAVES.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(base.offset(1, 4, 0), Blocks.OAK_LEAVES.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(base.offset(-1, 4, 0), Blocks.OAK_LEAVES.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(base.offset(0, 4, 1), Blocks.OAK_LEAVES.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(base.offset(0, 4, -1), Blocks.OAK_LEAVES.defaultBlockState(), Block.UPDATE_ALL);
	}

	/** 清掉已种下的树（供 teardown；通常由下一任务的平台重建兜底，无需显式调用）。 */
	static void removeTree(E2EContext ctx) {
		ServerLevel level = ctx.level();
		BlockPos base = treeBase(ctx);
		for (int dx = -2; dx <= 2; dx++) {
			for (int dy = 0; dy <= 5; dy++) {
				for (int dz = -2; dz <= 2; dz++) {
					level.setBlock(base.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				}
			}
		}
	}

	// ------------------------------------------------------------------
	// 容器任务场景：在平台上放一个箱子（助手容器交互测试用）
	// ------------------------------------------------------------------

	/** 容器位置（平台上，y = 平台顶 + 1，助手出生点旁边）。 */
	static BlockPos containerPos(E2EContext ctx) {
		return new BlockPos(ctx.areaOrigin().getX() + 3, ctx.areaOrigin().getY() + 1, ctx.areaOrigin().getZ());
	}

	/** 在容器位置放一个箱子（服务端线程）。 */
	static BlockPos placeChest(E2EContext ctx) {
		ServerLevel level = ctx.level();
		BlockPos pos = containerPos(ctx);
		level.setBlock(pos, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
		return pos;
	}

	/** 往箱子第 0 格放入物品（服务端线程；返回箱子位置）。 */
	static BlockPos fillChest(E2EContext ctx, net.minecraft.world.item.Item item, int count) {
		BlockPos pos = placeChest(ctx);
		if (ctx.level().getBlockEntity(pos)
				instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest) {
			chest.setItem(0, new net.minecraft.world.item.ItemStack(item, count));
		}
		return pos;
	}

	/** 在容器位置放一个熔炉（服务端线程）。 */
	static BlockPos placeFurnace(E2EContext ctx) {
		ServerLevel level = ctx.level();
		BlockPos pos = containerPos(ctx);
		level.setBlock(pos, net.minecraft.world.level.block.Blocks.FURNACE.defaultBlockState(), Block.UPDATE_ALL);
		return pos;
	}

	/**
	 * 往熔炉输入槽（槽 2）放入待烧物品（服务端线程；返回熔炉位置）。
	 * 熔炉槽位：0=成品、1=燃料、2=输入。
	 */
	static BlockPos fillFurnaceInput(E2EContext ctx, net.minecraft.world.item.Item item, int count) {
		BlockPos pos = placeFurnace(ctx);
		if (ctx.level().getBlockEntity(pos)
				instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace) {
			furnace.setItem(2, new net.minecraft.world.item.ItemStack(item, count));
		}
		return pos;
	}

	/** 在平台上放一个工作台（服务端线程；位置在助手出生点旁）。 */
	static BlockPos placeWorkbench(E2EContext ctx) {
		ServerLevel level = ctx.level();
		BlockPos pos = new BlockPos(ctx.areaOrigin().getX() + 2, ctx.areaOrigin().getY() + 1, ctx.areaOrigin().getZ() + 2);
		level.setBlock(pos, net.minecraft.world.level.block.Blocks.CRAFTING_TABLE.defaultBlockState(), Block.UPDATE_ALL);
		return pos;
	}

	/**
	 * 在平台面上放一块矿石（y = 平台顶 + 1，放在平台表面而非挖洞埋入，避免挖矿后在
	 * 平台上留洞导致 bot 掉落）。index 按 3×3 网格散布在平台内（x 偏 5-7、z 偏 -2/0/2，
	 * 全部在平台边界内，不会靠边掉落）。
	 */
	static BlockPos placeOre(E2EContext ctx, net.minecraft.world.level.block.Block ore, int index) {
		ServerLevel level = ctx.level();
		int gx = index % 3;
		int gz = index / 3;
		int ox = 5 + gx;          // 5, 6, 7
		int oz = -2 + gz * 2;     // -2, 0, 2
		BlockPos pos = new BlockPos(ctx.areaOrigin().getX() + ox, ctx.areaOrigin().getY() + 1,
				ctx.areaOrigin().getZ() + oz);
		level.setBlock(pos, ore.defaultBlockState(), Block.UPDATE_ALL);
		return pos;
	}
}
