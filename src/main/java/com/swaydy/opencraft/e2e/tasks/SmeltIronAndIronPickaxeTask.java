package com.swaydy.opencraft.e2e.tasks;

import com.swaydy.opencraft.e2e.E2EContext;
import com.swaydy.opencraft.e2e.E2ETask;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 内置 e2e 任务「smelt_iron_and_iron_pickaxe」：烧铁锭，合成铁镐（钻石镐任务线第 10-11 节点）。
 *
 * <p>场景：平台放好熔炉 + 工作台，助手背包有 3 块原铁、4 块煤炭、2 根木棍。助手打开熔炉、
 * 放入原铁（输入槽）+ 煤炭（燃料槽），等烧完取铁锭，再用铁锭+木棍在工做台合成铁镐。
 * 验证：背包/主人有 {@code iron_pickaxe}。</p>
 */
public class SmeltIronAndIronPickaxeTask implements E2ETask {

	@Override
	public String id() {
		return "smelt_iron_and_iron_pickaxe";
	}

	@Override
	public String description() {
		return "用熔炉烧铁锭，合成铁镐";
	}

	@Override
	public String taskPrompt() {
		return "直接开始执行，不要向我确认或提问。旁边有一个熔炉和一个工作台，你背包里有 3 块原铁、"
				+ "4 块煤炭和 2 根木棍。请打开熔炉：把原铁放进去烧，把煤炭放进去当燃料，"
				+ "等它烧完（用 player_container_list 查看成品槽），取出铁锭，"
				+ "再用铁锭和木棍在工作台合成一把铁镐。";
	}

	@Override
	public long timeoutMillis() {
		return 4 * 60_000L;
	}

	@Override
	public void setup(E2EContext ctx) {
		TaskScenes.placeWorkbench(ctx);
		TaskScenes.placeFurnace(ctx);
		ctx.assistant().getInventory().add(new ItemStack(Items.RAW_IRON, 3));
		ctx.assistant().getInventory().add(new ItemStack(Items.COAL, 4));
		ctx.assistant().getInventory().add(new ItemStack(Items.STICK, 2));
	}

	@Override
	public boolean verify(E2EContext ctx) {
		return ctx.countInInventory("minecraft:iron_pickaxe") >= 1
				|| ctx.countInOwnerInventory("minecraft:iron_pickaxe") >= 1;
	}
}