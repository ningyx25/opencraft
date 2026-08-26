package com.swaydy.opencraft.e2e.tasks;

import com.swaydy.opencraft.e2e.E2EContext;
import com.swaydy.opencraft.e2e.E2ETask;

/**
 * 内置 e2e 任务「craft_wooden_pickaxe」：做一把木镐。
 *
 * <p>场景：平台上种一棵橡树（提供木材）。验证：助手背包里有
 * {@code wooden_pickaxe}（完整工具链：砍树 → 木板 → 木棍 → 工作台 → 木镐，
 * 依赖 general_agent 的 gather-wood / craft-toolchain 技能）。</p>
 */
public class CraftWoodenPickaxeTask implements E2ETask {

	@Override
	public String id() {
		return "craft_wooden_pickaxe";
	}

	@Override
	public String description() {
		return "用旁边的树做一把木镐";
	}

	@Override
	public String taskPrompt() {
		return "直接开始执行，不要向我确认或提问。请用旁边的树做一把木镐（原木 → 木板 → 木棍 → 工作台 → 木镐，做出来拿在手上或放背包里）。";
	}

	@Override
	public void setup(E2EContext ctx) {
		TaskScenes.plantTree(ctx);
	}

	@Override
	public boolean verify(E2EContext ctx) {
		// 木镐可能留在助手背包，也可能被助手递给主人
		return ctx.countInInventory("minecraft:wooden_pickaxe") >= 1
				|| ctx.countInOwnerInventory("minecraft:wooden_pickaxe") >= 1;
	}
}
