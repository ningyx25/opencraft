package com.swaydy.opencraft.e2e.tasks;

import com.swaydy.opencraft.e2e.E2EContext;
import com.swaydy.opencraft.e2e.E2ETask;
import net.minecraft.core.BlockPos;

/**
 * 内置 e2e 任务「place_workbench」：合成一个工作台并放置在空地上。
 *
 * <p>场景：平台上种一棵橡树（提供木材）。验证：测试区内存在
 * {@code crafting_table} 方块（助手用真实放置链路放下的）。</p>
 */
public class PlaceWorkbenchTask implements E2ETask {

	@Override
	public String id() {
		return "place_workbench";
	}

	@Override
	public String description() {
		return "合成一个工作台并放置在空地上";
	}

	@Override
	public String taskPrompt() {
		return "直接开始执行，不要向我确认或提问。请用旁边的树做木板，合成一个工作台，然后把它放置在平台空地上（用 player_place 放置，别放在箱子上）。";
	}

	@Override
	public void setup(E2EContext ctx) {
		TaskScenes.plantTree(ctx);
	}

	@Override
	public boolean verify(E2EContext ctx) {
		BlockPos center = ctx.areaOrigin();
		// 工作台由助手放在平台范围内（含平台边缘一圈）
		return ctx.hasBlockInRegion("minecraft:crafting_table", center, com.swaydy.opencraft.e2e.E2EHarness.platformRadius() + 2);
	}
}
