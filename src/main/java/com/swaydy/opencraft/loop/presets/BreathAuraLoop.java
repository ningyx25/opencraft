package com.swaydy.opencraft.loop.presets;

import com.swaydy.opencraft.loop.LoopCondition;
import com.swaydy.opencraft.loop.LoopContext;
import com.swaydy.opencraft.loop.LoopEvent;
import com.swaydy.opencraft.loop.LoopMonitor;
import com.swaydy.opencraft.loop.LoopVerdict;
import com.swaydy.opencraft.logging.DebugLog;
import net.minecraft.server.level.ServerPlayer;

/**
 * 内置循环事件 {@code breath_aura}（换气光环）：绑定方块的主人玩家氧气不满
 * （溺水/缺氧）时,每 0.5 秒快速恢复氧气,直到离水回满。
 *
 * <p>循环语义（{@code 触发条件 → 执行事件 → 监测条件 → 触发条件 → …}）：
 * <ul>
 * <li><b>触发条件</b>：主人在线、存活、且氧气值不满;</li>
 * <li><b>执行事件</b>：恢复一批氧气;</li>
 * <li><b>监测函数</b>：氧气仍不满 → {@link LoopVerdict#CONTINUE} 继续下一轮;
 *     否则 → {@link LoopVerdict#STOP}——persistent,实例回等待继续监视。</li>
 * </ul>
 *
 * <p>参数：每 0.5 秒（10 tick）一轮——溺水掉血快,间隔取短;不限制迭代;persistent=true。
 */
public class BreathAuraLoop extends LoopPreset {
	/** 循环事件 id（LoopRegistry 键）。 */
	public static final String ID = "breath_aura";
	/** 每轮间隔：10 tick = 0.5 秒（溺水是急症,间隔取短）。 */
	private static final int INTERVAL_TICKS = 10;
	/** 每轮恢复的氧气量（原版上限 300 = 15 个氧气泡,60 = 3 个气泡）。 */
	private static final int AIR_PER_ROUND = 60;

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "换气光环";
	}

	@Override
	public String description() {
		return "当绑定方块的主人玩家氧气不满（溺水）时,每 0.5 秒快速恢复氧气,直到离水（持续守护）。";
	}

	@Override
	public LoopCondition trigger() {
		return BreathAuraLoop::ownerStillLowOnAir;
	}

	@Override
	public LoopEvent event() {
		return BreathAuraLoop::restoreAir;
	}

	@Override
	public LoopMonitor monitor() {
		return ctx -> ownerStillLowOnAir(ctx) ? LoopVerdict.CONTINUE : LoopVerdict.STOP;
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

	/** 触发条件 + 监测条件的共同判定：主人是否"需要换气"。 */
	private static boolean ownerStillLowOnAir(LoopContext ctx) {
		ServerPlayer owner = Owners.ownerOf(ctx);
		return owner != null && owner.isAlive()
				&& owner.getAirSupply() < owner.getMaxAirSupply();
	}

	/** 执行事件：给主人恢复一批氧气。 */
	private static void restoreAir(LoopContext ctx) {
		ServerPlayer owner = Owners.ownerOf(ctx);
		if (owner == null) {
			return;
		}
		int restored = Math.min(owner.getAirSupply() + AIR_PER_ROUND, owner.getMaxAirSupply());
		owner.setAirSupply(restored);
		DebugLog.log("loop",
				"breath_aura: 换气 {}（氧气 {}/{}）",
				owner.getName().getString(),
				restored, owner.getMaxAirSupply());
	}
}
