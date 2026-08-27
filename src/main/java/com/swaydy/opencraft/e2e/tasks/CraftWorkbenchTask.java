package com.swaydy.opencraft.e2e.tasks;

import com.swaydy.opencraft.e2e.E2EContext;
import com.swaydy.opencraft.e2e.E2ETask;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 内置 e2e 任务「craft_workbench」：用原木合成工作台（钻石镐任务线第 2 节点）。
 *
 * <p>场景：给助手 4 块橡木原木。验证：背包/主人有 crafting_table。</p>
 */
public class CraftWorkbenchTask implements E2ETask {

	@Override
	public String id() {
		return "craft_workbench";
	}

	@Override
	public String description() {
		return "用原木合成一个工作台";
	}

	@Override
	public String taskPrompt() {
		return "直接开始执行，不要向我确认或提问。你背包里有 4 块橡木原木，请把它们合成一个工作台"
				+ "（原木 → 木板 → 工作台）。";
	}

	@Override
	public long timeoutMillis() {
		return 4 * 60_000L;
	}

	@Override
	public void setup(E2EContext ctx) {
		ctx.assistant().getInventory().add(new ItemStack(Items.OAK_LOG, 4));
	}

	@Override
	public boolean verify(E2EContext ctx) {
		return ctx.countInInventory("minecraft:crafting_table") >= 1
				|| ctx.countInOwnerInventory("minecraft:crafting_table") >= 1;
	}
}