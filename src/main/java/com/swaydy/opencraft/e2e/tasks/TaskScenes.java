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
}
