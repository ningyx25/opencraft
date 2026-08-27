package com.swaydy.opencraft.e2e.tasks;

import com.swaydy.opencraft.e2e.E2EContext;
import com.swaydy.opencraft.e2e.E2ETask;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * 内置 e2e 任务「craft_furnace」：挖铁/煤/石头，合成熔炉（钻石镐任务线第 6-9 节点）。
 *
 * <p>场景：给石镐 + 平台放好工作台 + 平台埋铁矿石×3/煤炭矿石×3。助手用石镐挖铁矿石→
 * 原铁、煤炭矿石→煤炭、8 块石头→8 圆石，用圆石合成熔炉（需工作台 3×3）。
 * 验证：背包/主人有熔炉 + 原铁 + 煤炭。</p>
 */
public class CraftFurnaceTask implements E2ETask {

	@Override
	public String id() {
		return "craft_furnace";
	}

	@Override
	public String description() {
		return "挖铁/煤/石头，合成一个熔炉";
	}

	@Override
	public String taskPrompt() {
		return "直接开始执行，不要向我确认或提问。旁边有工作台，平台上有铁矿石和煤炭矿石。"
				+ "请用石镐挖 3 块铁矿石得到原铁、挖 3 块煤炭矿石得到煤炭，再挖 8 块普通石头，"
				+ "用圆石合成一个熔炉。";
	}

	@Override
	public long timeoutMillis() {
		return 4 * 60_000L;
	}

	@Override
	public void setup(E2EContext ctx) {
		TaskScenes.placeWorkbench(ctx);
		// 平台埋矿石：3 铁 + 3 煤，位置错开
		for (int i = 0; i < 3; i++) {
			TaskScenes.placeOre(ctx, Blocks.IRON_ORE, i);
		}
		for (int i = 0; i < 3; i++) {
			TaskScenes.placeOre(ctx, Blocks.COAL_ORE, i + 3);
		}
		// 给石镐
		ctx.assistant().getInventory().add(new ItemStack(Items.STONE_PICKAXE, 1));
	}

	@Override
	public boolean verify(E2EContext ctx) {
		// 熔炉成品入包 + 至少 1 原铁 + 至少 1 煤炭
		boolean furnace = ctx.countInInventory("minecraft:furnace") >= 1
				|| ctx.countInOwnerInventory("minecraft:furnace") >= 1;
		boolean iron = ctx.countInInventory("minecraft:raw_iron") + ctx.countInOwnerInventory("minecraft:raw_iron") >= 1;
		boolean coal = ctx.countInInventory("minecraft:coal") + ctx.countInOwnerInventory("minecraft:coal") >= 1;
		return furnace && iron && coal;
	}
}