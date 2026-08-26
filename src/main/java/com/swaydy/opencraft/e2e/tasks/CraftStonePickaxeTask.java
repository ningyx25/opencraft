package com.swaydy.opencraft.e2e.tasks;

import com.swaydy.opencraft.e2e.E2EContext;
import com.swaydy.opencraft.e2e.E2ETask;

/**
 * 内置 e2e 任务「craft_stone_pickaxe」：做一把石镐。
 *
 * <p>场景：平台上种一棵橡树（提供木材），平台本身是石头（提供圆石原料）。
 * 验证：助手背包里有 {@code stone_pickaxe}（完整工具链：砍树 → 木镐 → 采石头 → 石镐）。
 * 比木镐任务多一步实际使用工具，是更深度的 agentic loop 能力验证。</p>
 */
public class CraftStonePickaxeTask implements E2ETask {

	@Override
	public String id() {
		return "craft_stone_pickaxe";
	}

	@Override
	public String description() {
		return "做一把石镐";
	}

	@Override
	public String taskPrompt() {
		return "直接开始执行，不要向我确认或提问。请做一把石镐（先做木镐，用木镐挖石头得到圆石，再合成石镐）。";
	}

	@Override
	public void setup(E2EContext ctx) {
		TaskScenes.plantTree(ctx); // 木材来源
		// 平台本身就是石头（准备区域时已铺好），助手可以直接用木镐挖
	}

	@Override
	public boolean verify(E2EContext ctx) {
		// 石镐可能留在助手背包，也可能被助手递给主人（实测助手会 player_hand_to_player）
		return ctx.countInInventory("minecraft:stone_pickaxe") >= 1
				|| ctx.countInOwnerInventory("minecraft:stone_pickaxe") >= 1;
	}
}