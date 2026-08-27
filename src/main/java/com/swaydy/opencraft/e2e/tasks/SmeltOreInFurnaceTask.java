package com.swaydy.opencraft.e2e.tasks;

import com.swaydy.opencraft.e2e.E2EContext;
import com.swaydy.opencraft.e2e.E2ETask;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 内置 e2e 任务「smelt_ore_in_furnace」：在熔炉里烧制矿石并取出成品。
 *
 * <p>场景：平台上放一个空熔炉，助手背包有 1 块原铁 + 8 块煤炭。助手用
 * {@code player_container_open/list/put/take/close} 把原铁放进输入槽、煤炭放进燃料槽，
 * 等熔炉烧完（约 10s），取出铁锭。验证：助手背包或熔炉成品槽有铁锭。</p>
 */
public class SmeltOreInFurnaceTask implements E2ETask {

	@Override
	public String id() {
		return "smelt_ore_in_furnace";
	}

	@Override
	public String description() {
		return "在熔炉里烧制原铁并取出铁锭";
	}

	@Override
	public String taskPrompt() {
		return "直接开始执行，不要向我确认或提问。旁边有一个空熔炉，你背包里有 1 块原铁和 8 块煤炭。"
				+ "请打开熔炉：把原铁放进去烧，把煤炭放进去当燃料，等它烧完（用 player_container_list "
				+ "查看成品槽），把烧好的铁锭取出来放进背包，然后关上熔炉。";
	}

	@Override
	public long timeoutMillis() {
		return 4 * 60_000L;
	}

	@Override
	public void setup(E2EContext ctx) {
		// 空熔炉（不预填），给助手原铁 + 煤炭
		TaskScenes.placeFurnace(ctx);
		ctx.assistant().getInventory().add(new ItemStack(Items.RAW_IRON, 1));
		ctx.assistant().getInventory().add(new ItemStack(Items.COAL, 8));
	}

	@Override
	public boolean verify(E2EContext ctx) {
		BlockPos furnacePos = TaskScenes.containerPos(ctx);
		// 铁锭在助手背包（取出了）或熔炉成品槽（烧好了没取）
		return ctx.countInInventory("minecraft:iron_ingot") >= 1
				|| ctx.countInContainer(furnacePos, "minecraft:iron_ingot") >= 1;
	}
}