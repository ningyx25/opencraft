package com.swaydy.opencraft.e2e.tasks;

import com.swaydy.opencraft.e2e.E2EContext;
import com.swaydy.opencraft.e2e.E2ETask;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 内置 e2e 任务「store_items_in_chest」：把背包物品存入箱子。
 *
 * <p>场景：平台上放一个空箱子，助手背包里有 8 块橡木板。助手用
 * {@code player_container_open/put/close} 把木板放入箱子。验证：箱子有 8 块木板，
 * 助手背包无木板。</p>
 */
public class StoreItemsInChestTask implements E2ETask {

	@Override
	public String id() {
		return "store_items_in_chest";
	}

	@Override
	public String description() {
		return "把背包里的物品存入箱子";
	}

	@Override
	public String taskPrompt() {
		return "直接开始执行，不要向我确认或提问。你背包里有 8 块橡木板，旁边有一个空箱子，"
				+ "请打开箱子，把 8 块橡木板全部放进去，然后关上箱子。";
	}

	@Override
	public long timeoutMillis() {
		return 4 * 60_000L;
	}

	@Override
	public void setup(E2EContext ctx) {
		// 只放箱子（空），不预填物品
		TaskScenes.placeChest(ctx);
		// 给助手 8 块橡木板
		ctx.assistant().getInventory().add(new ItemStack(Items.OAK_PLANKS, 8));
	}

	@Override
	public boolean verify(E2EContext ctx) {
		BlockPos chestPos = TaskScenes.containerPos(ctx);
		// 箱子里有 8 块木板，且助手背包没有木板
		return ctx.countInContainer(chestPos, "minecraft:oak_planks") >= 8
				&& ctx.countInInventory("minecraft:oak_planks") == 0;
	}
}