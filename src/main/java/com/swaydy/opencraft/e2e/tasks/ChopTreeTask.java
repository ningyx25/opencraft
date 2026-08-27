package com.swaydy.opencraft.e2e.tasks;

import com.swaydy.opencraft.e2e.E2EContext;
import com.swaydy.opencraft.e2e.E2ETask;
import net.minecraft.core.BlockPos;

/**
 * 内置 e2e 任务「chop_tree」：砍一棵树并收集原木（钻石镐任务线第 1 节点）。
 *
 * <p>场景：平台上种一棵橡树，助手空手。验证：4 根原木入包/主人，区域无站立树干。</p>
 */
public class ChopTreeTask implements E2ETask {

	@Override
	public String id() {
		return "chop_tree";
	}

	@Override
	public String description() {
		return "砍一棵树并收集原木";
	}

	@Override
	public String taskPrompt() {
		return "直接开始执行，不要向我确认或提问。请把旁边那棵橡树砍倒，收集全部原木"
				+ "（用 player_find 找到树，用 player_mine 挖）。";
	}

	@Override
	public long timeoutMillis() {
		return 4 * 60_000L;
	}

	@Override
	public void setup(E2EContext ctx) {
		TaskScenes.plantTree(ctx);
	}

	@Override
	public boolean verify(E2EContext ctx) {
		BlockPos base = TaskScenes.treeBase(ctx);
		// 树干全砍掉（区域无站立 oak_log）且 ≥3 根原木入包/主人/地上
		int standing = ctx.countBlockInRegion("minecraft:oak_log", base, 3);
		if (standing > 0) {
			return false;
		}
		return ctx.countInInventory("minecraft:oak_log") + ctx.countInOwnerInventory("minecraft:oak_log") >= 3;
	}
}