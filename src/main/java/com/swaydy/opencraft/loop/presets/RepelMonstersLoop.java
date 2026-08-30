package com.swaydy.opencraft.loop.presets;

import com.swaydy.opencraft.loop.LoopCondition;
import com.swaydy.opencraft.loop.LoopContext;
import com.swaydy.opencraft.loop.LoopEvent;
import com.swaydy.opencraft.loop.LoopMonitor;
import com.swaydy.opencraft.loop.LoopVerdict;
import com.swaydy.opencraft.logging.DebugLog;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;

import java.util.List;

/**
 * 内置循环事件 {@code mob_repel}（驱怪光环）：绑定方块的主人玩家身边出现敌对生物时,
 * 把它们击退（不造成伤害——驱离而不是代打）。
 *
 * <p>循环语义（{@code 触发条件 → 执行事件 → 监测条件 → 触发条件 → …}）：
 * <ul>
 * <li><b>触发条件</b>：主人在线、存活,且 {@value #RADIUS} 格内有敌对生物
 *     （实现原版 {@link Enemy} 接口的生物,含苦力怕/僵尸/骷髅/史莱姆/幻翼等）;</li>
 * <li><b>执行事件</b>：将这些生物沿"远离主人"的方向击退一次
 *     （{@code knockback} 走原版路径,尊重生物的击退抗性）;</li>
 * <li><b>监测函数</b>：恒 {@link LoopVerdict#STOP}——每轮驱离一次即结束本轮,
 *     persistent 实例回等待,下个间隔再次监视（怪若再靠近会再次被推走）。</li>
 * </ul>
 *
 * <p>参数：每 1 秒（20 tick）一轮——僵尸从 {@value #RADIUS} 格走到近战距离约 2 秒,
 * 苦力怕引信 30 tick,间隔必须短于两者;不限制迭代;persistent=true（守护型）。
 */
public class RepelMonstersLoop extends LoopPreset {
	/** 循环事件 id（LoopRegistry 键）。 */
	public static final String ID = "mob_repel";
	/** 每轮间隔：20 tick = 1 秒（僵尸从 6 格走到近战距离约 2 秒,间隔必须短于它）。 */
	private static final int INTERVAL_TICKS = 20;
	/** 警戒半径（格）。 */
	private static final double RADIUS = 6.0;
	/** 击退强度（原版玩家空手攻击 ≈ 0.4,这里略强但不致摔伤）。 */
	private static final double KNOCKBACK_STRENGTH = 1.0;

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "驱怪光环";
	}

	@Override
	public String description() {
		return "当绑定方块的主人玩家 6 格内有敌对生物时,每 1 秒将其击退（不造成伤害,持续守护）。";
	}

	@Override
	public LoopCondition trigger() {
		return RepelMonstersLoop::hasHostilesNearby;
	}

	@Override
	public LoopEvent event() {
		return RepelMonstersLoop::repelHostiles;
	}

	@Override
	public LoopMonitor monitor() {
		// 每轮驱离一次即结束本轮;persistent 实例回等待继续监视
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
	// 三个组成部分（主人解析复用 Owners,缺失环节返回 null 由引擎按跳过处理）
	// ------------------------------------------------------------------

	/** 主人 {@value #RADIUS} 格内存活的敌对生物（{@link Enemy} 标记接口,含史莱姆/幻翼）。 */
	private static List<Mob> hostilesNear(ServerPlayer owner) {
		ServerLevel level = owner.level();
		return level.getEntitiesOfClass(Mob.class,
				owner.getBoundingBox().inflate(RADIUS),
				mob -> mob.isAlive() && mob instanceof Enemy);
	}

	/** 触发条件：主人在线存活,且身边有敌对生物。 */
	private static boolean hasHostilesNearby(LoopContext ctx) {
		ServerPlayer owner = Owners.ownerOf(ctx);
		return owner != null && owner.isAlive() && !hostilesNear(owner).isEmpty();
	}

	/** 执行事件：把敌对生物沿"远离主人"方向击退一次。 */
	private static void repelHostiles(LoopContext ctx) {
		ServerPlayer owner = Owners.ownerOf(ctx);
		if (owner == null) {
			return;
		}
		List<Mob> hostiles = hostilesNear(owner);
		for (Mob mob : hostiles) {
			// 原版攻击的用法：传"攻击者 - 目标"的水平向量,目标被推离攻击者
			mob.knockback(KNOCKBACK_STRENGTH,
					owner.getX() - mob.getX(), owner.getZ() - mob.getZ());
		}
		if (!hostiles.isEmpty()) {
			DebugLog.log("loop", "mob_repel: 驱退 {} 只敌对生物", hostiles.size());
		}
	}
}
