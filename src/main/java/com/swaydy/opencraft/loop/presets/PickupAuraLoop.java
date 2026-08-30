package com.swaydy.opencraft.loop.presets;

import com.swaydy.opencraft.assistant.player.AiAssistantPlayer;
import com.swaydy.opencraft.loop.LoopCondition;
import com.swaydy.opencraft.loop.LoopContext;
import com.swaydy.opencraft.loop.LoopEvent;
import com.swaydy.opencraft.loop.LoopMonitor;
import com.swaydy.opencraft.loop.LoopVerdict;
import com.swaydy.opencraft.logging.DebugLog;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 内置循环事件 {@code pickup_aura}（拾取光环）：自动把绑定助手身边 5 格内
 * <b>已可拾取</b>的掉落物拉向助手,由助手（真玩家接触拾取）收进背包。
 *
 * <p>循环语义（{@code 触发条件 → 执行事件 → 监测条件 → 触发条件 → …}）：
 * <ul>
 * <li><b>触发条件</b>：绑定助手存活,且身边 {@value #RADIUS} 格内有可收集的掉落物
 *     （过了原版拾取保护期、且不是助手自己刚丢弃的）;</li>
 * <li><b>执行事件</b>：给这些掉落物一个飞向助手的速度（越远越快）,
 *     物品飞到助手脚边后被原版接触拾取收入背包;</li>
 * <li><b>监测函数</b>：恒 {@link LoopVerdict#STOP}——每轮拉动一次即结束本轮,
 *     persistent 实例回等待,下个间隔再次监视。</li>
 * </ul>
 *
 * <p>参数：每 1 秒（20 tick）一轮;不限制迭代;persistent=true（守护型）。
 * 拾取方向是<b>助手</b>而不是主人：助手是"帮你收拾"的执行者,物品进助手背包后
 * 可经背包界面/递物交给主人;助手自己丢弃的物品不会被回收。
 */
public class PickupAuraLoop extends LoopPreset {
	/** 循环事件 id（LoopRegistry 键）。 */
	public static final String ID = "pickup_aura";
	/** 每轮间隔：20 tick = 1 秒。 */
	private static final int INTERVAL_TICKS = 20;
	/** 拉取半径（格）。 */
	private static final double RADIUS = 5.0;
	/** 拉动的基础速度（按距离加成,上限 {@value #MAX_PULL_SPEED}）。 */
	private static final double BASE_PULL_SPEED = 0.25;
	/** 每格距离的速度加成。 */
	private static final double SPEED_PER_BLOCK = 0.12;
	/** 拉动速度上限。 */
	private static final double MAX_PULL_SPEED = 0.9;
	/** 拉动时附加的向上速度（抵消重力,竖直方向也能拉）。 */
	private static final double PULL_UP_SPEED = 0.08;

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "拾取光环";
	}

	@Override
	public String description() {
		return "自动把绑定助手身边 5 格内可拾取的掉落物拉向助手,收进助手背包（持续守护）。";
	}

	@Override
	public LoopCondition trigger() {
		return PickupAuraLoop::hasCollectibleNearby;
	}

	@Override
	public LoopEvent event() {
		return PickupAuraLoop::pullItems;
	}

	@Override
	public LoopMonitor monitor() {
		// 每轮拉动一次即结束本轮;persistent 实例回等待继续监视
		return ctx -> LoopVerdict.STOP;
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
	// 三个组成部分（助手解析复用 Owners,缺失环节返回 null 由引擎按跳过处理）
	// ------------------------------------------------------------------

	/** 助手身边 {@value #RADIUS} 格内可收集的掉落物（可拾取、非助手自己丢弃）；背包已满时不收集。 */
	private static List<ItemEntity> collectibleNear(AiAssistantPlayer assistant) {
		// 背包满时不再拉动——物品收不进去,只会被反复推向助手堆在脚边
		if (assistant.getInventory().getFreeSlot() == -1) {
			return List.of();
		}
		return assistant.level().getEntitiesOfClass(ItemEntity.class,
				assistant.getBoundingBox().inflate(RADIUS),
				item -> item.isAlive() && !item.hasPickUpDelay()
						&& item.getOwner() != assistant);
	}

	/** 触发条件：绑定助手存活,且身边有可收集的掉落物。 */
	private static boolean hasCollectibleNearby(LoopContext ctx) {
		AiAssistantPlayer assistant = Owners.assistantOf(ctx);
		return assistant != null && !collectibleNear(assistant).isEmpty();
	}

	/** 执行事件：给可收集的掉落物一个飞向助手的速度（越远越快,含竖直方向）。 */
	private static void pullItems(LoopContext ctx) {
		AiAssistantPlayer assistant = Owners.assistantOf(ctx);
		if (assistant == null) {
			return;
		}
		List<ItemEntity> items = collectibleNear(assistant);
		for (ItemEntity item : items) {
			Vec3 toAssistant = assistant.position().subtract(item.position());
			double dist = toAssistant.length();
			if (dist < 0.75) {
				continue; // 已在脚边,原版接触拾取会自动收入
			}
			// 全 3D 方向拉动——只按水平算的话,正上/正下方（坑里/楼上）的物品永远拉不到
			double speed = Math.min(MAX_PULL_SPEED, BASE_PULL_SPEED + dist * SPEED_PER_BLOCK);
			item.setDeltaMovement(toAssistant.normalize().scale(speed).add(0, PULL_UP_SPEED, 0));
		}
		if (!items.isEmpty()) {
			DebugLog.log("loop", "pickup_aura: 拉动 {} 个掉落物飞向助手", items.size());
		}
	}
}
