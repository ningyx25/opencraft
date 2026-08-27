package com.swaydy.opencraft.e2e.tasks;

import com.swaydy.opencraft.e2e.E2EContext;
import com.swaydy.opencraft.e2e.E2ETask;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 内置 e2e 任务「add_fuel_and_ore_to_furnace」：往熔炉添加矿石和燃料。
 *
 * <p>场景：平台上放一个空熔炉，助手背包有 2 块原铁 + 4 块煤炭。助手用
 * {@code player_container_open/put/close} 把原铁放进输入槽、煤炭放进燃料槽。
 * 验证：熔炉输入槽有原铁、燃料槽有煤炭（不要求等烧完/取成品）。</p>
 */
public class AddFuelAndOreToFurnaceTask implements E2ETask {

	@Override
	public String id() {
		return "add_fuel_and_ore_to_furnace";
	}

	@Override
	public String description() {
		return "往熔炉里添加矿石和燃料";
	}

	@Override
	public String taskPrompt() {
		return "直接开始执行，不要向我确认或提问。旁边有一个空熔炉，你背包里有 2 块原铁和 4 块煤炭。"
				+ "请打开熔炉：把原铁放进熔炉里烧，把煤炭放进去当燃料，然后关上熔炉。";
	}

	@Override
	public long timeoutMillis() {
		return 4 * 60_000L;
	}

	@Override
	public void setup(E2EContext ctx) {
		// 空熔炉，给助手原铁 + 煤炭
		TaskScenes.placeFurnace(ctx);
		ctx.assistant().getInventory().add(new ItemStack(Items.RAW_IRON, 2));
		ctx.assistant().getInventory().add(new ItemStack(Items.COAL, 4));
	}

	@Override
	public boolean verify(E2EContext ctx) {
		BlockPos furnacePos = TaskScenes.containerPos(ctx);
		// 熔炉里有原铁（输入槽）和煤炭（燃料槽）
		return ctx.countInContainer(furnacePos, "minecraft:raw_iron") >= 1
				&& ctx.countInContainer(furnacePos, "minecraft:coal") >= 1;
	}
}