package com.swaydy.opencraft.loop.presets;

import com.swaydy.opencraft.loop.LoopCondition;
import com.swaydy.opencraft.loop.LoopContext;
import com.swaydy.opencraft.loop.LoopEvent;
import com.swaydy.opencraft.loop.LoopMonitor;
import com.swaydy.opencraft.loop.LoopVerdict;
import com.swaydy.opencraft.logging.DebugLog;
import net.minecraft.server.level.ServerPlayer;

/**
 * 内置循环事件 {@code extinguish_fire}（灭火守护）：绑定方块的主人玩家着火时,
 * 每 0.5 秒尝试灭火,直到火熄灭。
 *
 * <p>循环语义（{@code 触发条件 → 执行事件 → 监测条件 → 触发条件 → …}）：
 * <ul>
 * <li><b>触发条件</b>：主人在线、存活、且身上有火;</li>
 * <li><b>执行事件</b>：灭火（{@code extinguishFire}）;</li>
 * <li><b>监测函数</b>：仍在燃烧（如站在岩浆/火里）→ {@link LoopVerdict#CONTINUE}
 *     继续下一轮;否则 → {@link LoopVerdict#STOP}——persistent,实例回等待继续监视,
 *     主人再次着火时自动再灭。</li>
 * </ul>
 *
 * <p>参数：每 0.5 秒（10 tick）一轮——火烧人快,间隔取短;不限制迭代;persistent=true。
 */
public class ExtinguishLoop extends LoopPreset {
	/** 循环事件 id（LoopRegistry 键）。 */
	public static final String ID = "extinguish_fire";
	/** 每轮间隔：10 tick = 0.5 秒（火烧人快,间隔取短）。 */
	private static final int INTERVAL_TICKS = 10;

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "灭火守护";
	}

	@Override
	public String description() {
		return "当绑定方块的主人玩家着火时,每 0.5 秒尝试灭火,直到火熄灭（持续守护）。";
	}

	@Override
	public LoopCondition trigger() {
		return ExtinguishLoop::ownerStillOnFire;
	}

	@Override
	public LoopEvent event() {
		return ExtinguishLoop::extinguishOwner;
	}

	@Override
	public LoopMonitor monitor() {
		return ctx -> ownerStillOnFire(ctx) ? LoopVerdict.CONTINUE : LoopVerdict.STOP;
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

	/** 触发条件 + 监测条件的共同判定：主人是否"着火"。 */
	private static boolean ownerStillOnFire(LoopContext ctx) {
		ServerPlayer owner = Owners.ownerOf(ctx);
		return owner != null && owner.isAlive() && owner.isOnFire();
	}

	/** 执行事件：灭火。 */
	private static void extinguishOwner(LoopContext ctx) {
		ServerPlayer owner = Owners.ownerOf(ctx);
		if (owner == null) {
			return;
		}
		owner.extinguishFire();
		DebugLog.log("loop", "extinguish_fire: 灭火 {}", owner.getName().getString());
	}
}
