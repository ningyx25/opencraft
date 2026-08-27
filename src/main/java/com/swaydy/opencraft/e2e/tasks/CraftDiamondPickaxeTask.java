package com.swaydy.opencraft.e2e.tasks;

import com.swaydy.opencraft.e2e.E2EContext;
import com.swaydy.opencraft.e2e.E2ETask;
import net.minecraft.world.level.block.Blocks;

/**
 * 内置 e2e 任务「craft_diamond_pickaxe」：从零开始做一把钻石镐（最终任务，完整 13 节点）。
 *
 * <p>初始条件 = 刚进游戏：空手、空背包。平台上有 1 棵橡树 + 铁矿石×3 + 煤炭矿石×3 +
 * 钻石矿石×3（均在平台内）。助手需走完整工具链：
 * 砍树→木板→木棍→工作台→木镐→挖石头(圆石)→石镐→挖铁矿石(原铁)/煤炭矿石(煤炭)→
 * 挖8石头→熔炉→烧铁锭→铁镐→挖钻石矿石(钻石)→钻石镐。
 * 验证：背包/主人有 {@code diamond_pickaxe}。</p>
 */
public class CraftDiamondPickaxeTask implements E2ETask {

	@Override
	public String id() {
		return "craft_diamond_pickaxe";
	}

	@Override
	public String description() {
		return "从零开始做一把钻石镐（完整任务线）";
	}

	@Override
	public String taskPrompt() {
		return "直接开始执行，不要向我确认或提问。你刚进入游戏，身上什么都没有。"
				+ "平台上有 1 棵橡树、铁矿石、煤炭矿石和钻石矿石。请从零开始做一把钻石镐，"
				+ "完整流程：砍树收集原木 → 做工作台 → 做木镐 → 挖石头得圆石 → 做石镐 → "
				+ "挖铁矿石得原铁、挖煤炭矿石得煤炭 → 再挖 8 块石头 → 做熔炉 → "
				+ "用熔炉烧铁锭 → 做铁镐 → 挖钻石矿石得钻石 → 合成钻石镐。";
	}

	@Override
	public long timeoutMillis() {
		// 完整 13 节点工具链，给足时间（约 5-15 分钟）
		return 15 * 60_000L;
	}

	@Override
	public void setup(E2EContext ctx) {
		// 初始状态 = 刚进游戏：只提供场景（树 + 矿石），不给任何物品/工具/工作台
		TaskScenes.plantTree(ctx);
		for (int i = 0; i < 3; i++) {
			TaskScenes.placeOre(ctx, Blocks.IRON_ORE, i);      // index 0-2
		}
		for (int i = 0; i < 3; i++) {
			TaskScenes.placeOre(ctx, Blocks.COAL_ORE, i + 3);   // index 3-5
		}
		for (int i = 0; i < 3; i++) {
			TaskScenes.placeOre(ctx, Blocks.DIAMOND_ORE, i + 6); // index 6-8
		}
	}

	@Override
	public boolean verify(E2EContext ctx) {
		return ctx.countInInventory("minecraft:diamond_pickaxe") >= 1
				|| ctx.countInOwnerInventory("minecraft:diamond_pickaxe") >= 1;
	}
}