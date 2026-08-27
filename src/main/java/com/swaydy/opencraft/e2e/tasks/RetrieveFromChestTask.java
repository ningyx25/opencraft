package com.swaydy.opencraft.e2e.tasks;

import com.swaydy.opencraft.e2e.E2EContext;
import com.swaydy.opencraft.e2e.E2ETask;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;

/**
 * 内置 e2e 任务「retrieve_from_chest」：从箱子取出物品。
 *
 * <p>场景：平台上放一个预填了 8 块圆石的箱子。助手用
 * {@code player_container_open/list/take/close} 把圆石取入背包。验证：助手背包有
 * 圆石，箱子空了。</p>
 */
public class RetrieveFromChestTask implements E2ETask {

	@Override
	public String id() {
		return "retrieve_from_chest";
	}

	@Override
	public String description() {
		return "从箱子取出物品";
	}

	@Override
	public String taskPrompt() {
		return "直接开始执行，不要向我确认或提问。旁边有一个箱子，里面有 8 块圆石，"
				+ "请打开箱子，把里面的圆石全部拿出来放进背包，然后关上箱子。";
	}

	@Override
	public long timeoutMillis() {
		return 4 * 60_000L;
	}

	@Override
	public void setup(E2EContext ctx) {
		// 预填箱子：8 块圆石在第 0 格
		TaskScenes.fillChest(ctx, Items.COBBLESTONE, 8);
	}

	@Override
	public boolean verify(E2EContext ctx) {
		BlockPos chestPos = TaskScenes.containerPos(ctx);
		// 助手背包有 8 块圆石，箱子没有圆石
		return ctx.countInInventory("minecraft:cobblestone") >= 8
				&& ctx.countInContainer(chestPos, "minecraft:cobblestone") == 0;
	}
}