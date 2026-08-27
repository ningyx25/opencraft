package com.swaydy.opencraft.e2e.tasks;

import com.swaydy.opencraft.e2e.E2EContext;
import com.swaydy.opencraft.e2e.E2ETask;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 内置 e2e 任务「organize_container」：整理箱子——同时做取出和放入。
 *
 * <p>场景：平台上放一个预填了 8 块圆石的箱子，助手背包有 8 块橡木板。助手用
 * {@code player_container_open/list/take/put/close} 取走圆石、放入木板。验证：助手
 * 背包有圆石、箱子有木板、箱子无圆石。</p>
 */
public class OrganizeContainerTask implements E2ETask {

	@Override
	public String id() {
		return "organize_container";
	}

	@Override
	public String description() {
		return "整理箱子（取出+放入）";
	}

	@Override
	public String taskPrompt() {
		return "直接开始执行，不要向我确认或提问。旁边有一个箱子，里面有 8 块圆石，"
				+ "你背包里有 8 块橡木板。请打开箱子：把箱子里的圆石全部拿出来放进背包，"
				+ "把你背包里的橡木板全部放进去，然后关上箱子。";
	}

	@Override
	public long timeoutMillis() {
		return 4 * 60_000L;
	}

	@Override
	public void setup(E2EContext ctx) {
		// 预填箱子：8 块圆石在第 0 格
		TaskScenes.fillChest(ctx, Items.COBBLESTONE, 8);
		// 给助手 8 块橡木板
		ctx.assistant().getInventory().add(new ItemStack(Items.OAK_PLANKS, 8));
	}

	@Override
	public boolean verify(E2EContext ctx) {
		BlockPos chestPos = TaskScenes.containerPos(ctx);
		// 助手背包有 8 块圆石，箱子里有 8 块木板，箱子没有圆石
		return ctx.countInInventory("minecraft:cobblestone") >= 8
				&& ctx.countInContainer(chestPos, "minecraft:oak_planks") >= 8
				&& ctx.countInContainer(chestPos, "minecraft:cobblestone") == 0;
	}
}