package com.swaydy.opencraft.e2e.tasks;

import com.swaydy.opencraft.e2e.E2EContext;
import com.swaydy.opencraft.e2e.E2ETask;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 内置 e2e 任务「craft_wooden_pickaxe」：用原木做一把木镐（钻石镐任务线第 3 节点）。
 *
 * <p>场景：给助手 8 块橡木原木 + 平台放好工作台（3×3 合成需要）。验证：背包/主人有
 * {@code wooden_pickaxe}。</p>
 */
public class CraftWoodenPickaxeTask implements E2ETask {

	@Override
	public String id() {
		return "craft_wooden_pickaxe";
	}

	@Override
	public String description() {
		return "用原木做一把木镐";
	}

	@Override
	public String taskPrompt() {
		return "直接开始执行，不要向我确认或提问。你背包里有 8 块橡木原木，旁边有工作台，"
				+ "请做一把木镐（原木 → 木板 → 木棍 → 木镐）。";
	}

	@Override
	public long timeoutMillis() {
		return 4 * 60_000L;
	}

	@Override
	public void setup(E2EContext ctx) {
		TaskScenes.placeWorkbench(ctx);
		ctx.assistant().getInventory().add(new ItemStack(Items.OAK_LOG, 8));
	}

	@Override
	public boolean verify(E2EContext ctx) {
		return ctx.countInInventory("minecraft:wooden_pickaxe") >= 1
				|| ctx.countInOwnerInventory("minecraft:wooden_pickaxe") >= 1;
	}
}