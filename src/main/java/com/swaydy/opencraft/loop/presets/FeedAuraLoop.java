package com.swaydy.opencraft.loop.presets;

import com.swaydy.opencraft.loop.LoopCondition;
import com.swaydy.opencraft.loop.LoopContext;
import com.swaydy.opencraft.loop.LoopEvent;
import com.swaydy.opencraft.loop.LoopMonitor;
import com.swaydy.opencraft.loop.LoopVerdict;
import com.swaydy.opencraft.logging.DebugLog;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;

/**
 * 内置循环事件 {@code feed_aura}（饱食光环）：绑定方块的主人玩家饥饿值不满时,
 * 每 2 秒恢复 1 点饥饿值,直到吃饱。
 *
 * <p>循环语义（{@code 触发条件 → 执行事件 → 监测条件 → 触发条件 → …}）：
 * <ul>
 * <li><b>触发条件</b>：主人在线、存活、且饥饿值不满;</li>
 * <li><b>执行事件</b>：恢复 1 点饥饿值;</li>
 * <li><b>监测函数</b>：仍饥饿 → {@link LoopVerdict#CONTINUE} 继续下一轮;
 *     否则 → {@link LoopVerdict#STOP}——persistent,实例回等待继续监视,
 *     主人再次饥饿时自动再喂。</li>
 * </ul>
 *
 * <p>参数：每 2 秒（40 tick）一轮;不限制总迭代次数;persistent=true（守护型）。
 * 主人饥饿值 ≥ 18 后原版自然回血恢复生效——与 heal_aura 组成「保命」组合。
 */
public class FeedAuraLoop extends LoopPreset {
	/** 循环事件 id（LoopRegistry 键）。 */
	public static final String ID = "feed_aura";
	/** 每轮间隔：40 tick = 2 秒。 */
	private static final int INTERVAL_TICKS = 40;
	/** 原版玩家饥饿值上限。 */
	private static final int MAX_FOOD_LEVEL = 20;
	/** 每轮恢复的饥饿值。 */
	private static final int FEED_AMOUNT = 1;

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "饱食光环";
	}

	@Override
	public String description() {
		return "当绑定方块的主人玩家饥饿值不满时,每 2 秒恢复 1 点饥饿值,直到吃饱（持续守护）。";
	}

	@Override
	public LoopCondition trigger() {
		return FeedAuraLoop::ownerStillHungry;
	}

	@Override
	public LoopEvent event() {
		return FeedAuraLoop::feedOwner;
	}

	@Override
	public LoopMonitor monitor() {
		return ctx -> ownerStillHungry(ctx) ? LoopVerdict.CONTINUE : LoopVerdict.STOP;
	}

	@Override
	public int intervalTicks() {
		return INTERVAL_TICKS;
	}

	@Override
	public boolean persistent() {
		return true;
	}

	// ------------------------------------------------------------------
	// 三个组成部分（主人解析复用 Owners,缺失环节返回 null 由引擎按跳过处理）
	// ------------------------------------------------------------------

	/** 触发条件 + 监测条件的共同判定：主人是否"需要进食"。 */
	private static boolean ownerStillHungry(LoopContext ctx) {
		ServerPlayer owner = Owners.ownerOf(ctx);
		return owner != null && owner.isAlive()
				&& owner.getFoodData().getFoodLevel() < MAX_FOOD_LEVEL;
	}

	/** 执行事件：给主人恢复 1 点饥饿值。 */
	private static void feedOwner(LoopContext ctx) {
		ServerPlayer owner = Owners.ownerOf(ctx);
		if (owner == null) {
			return;
		}
		FoodData food = owner.getFoodData();
		food.setFoodLevel(Math.min(food.getFoodLevel() + FEED_AMOUNT, MAX_FOOD_LEVEL));
		DebugLog.log("loop",
				"feed_aura: 喂食 {}（饥饿 {}/{}）",
				owner.getName().getString(),
				food.getFoodLevel(), MAX_FOOD_LEVEL);
	}
}
