package com.swaydy.opencraft.loop;

import com.swaydy.opencraft.OpenCraftMod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 循环事件引擎：驱动所有活动循环实例的静态调度器。
 *
 * <p><b>循环语义</b>（{@code 触发条件 → 执行事件 → 监测条件 → 触发条件 → …}）：
 * 每个实例是一个三阶段状态机（{@link LoopPhase}）,每个服务端 tick 推进一个阶段：
 * <ol>
 * <li>{@code WAITING}——受 {@code intervalTicks} 间隔门控地评估<b>触发条件</b>
 *     （不通过 → 本轮跳过,设下次评估 tick;通过 → 进入执行）;</li>
 * <li>{@code EXECUTING}——执行一次<b>事件</b>,迭代计数 +1;</li>
 * <li>{@code MONITORING}——执行<b>监测函数</b>：{@link LoopVerdict#CONTINUE} 回到 WAITING;
 *     {@link LoopVerdict#STOP} 时,persistent 实例回到 WAITING 继续监视,一次性实例被移除。</li>
 * </ol>
 *
 * <p><b>守卫</b>（全部在引擎内 try/catch,异常绝不抛给调用方）：
 * <ul>
 * <li>触发条件抛异常 → 按不通过处理并累计连续错误,连续 ≥ {@value #MAX_CONSECUTIVE_ERRORS}
 *     次停止实例（防"条件永远抛异常"的僵尸实例）;</li>
 * <li>事件/监测函数抛异常 → 立即停止实例并告警;</li>
 * <li>{@code maxIterations} 达到 → 正常停止实例。</li>
 * </ul>
 *
 * <p><b>纯 Java、无 Minecraft 依赖</b>（日志走 SLF4J,与 {@code AgentRegistry} 一致）,
 * 可在纯 JUnit 下用假条件/事件/监测函数单测;Minecraft 对象由接线层在 lambda 闭包中提供。
 * 线程：所有方法都在服务端线程调用（tick 驱动）,实例表用 ConcurrentHashMap 防御并发。
 */
public final class LoopEngine {
	/** 触发条件连续失败的容忍上限：超过即停止实例。 */
	public static final int MAX_CONSECUTIVE_ERRORS = 5;

	/** 活动实例表：按 {@code (anchor, defId)} 键控;一个锚点可同时跑多个不同定义。 */
	private static final Map<LoopKey, LoopInstance> INSTANCES = new ConcurrentHashMap<>();

	private LoopEngine() {
	}

	// ------------------------------------------------------------------
	// 生命周期
	// ------------------------------------------------------------------

	/**
	 * 在指定锚点启动一个循环实例（幂等：同一 anchor + defId 已有实例时不重复创建）。
	 * 必须在服务端线程调用。
	 */
	public static void start(LoopDefinition def, Object anchor) {
		if (def == null || anchor == null) {
			return;
		}
		LoopKey key = new LoopKey(anchor, def.id());
		INSTANCES.computeIfAbsent(key, k -> new LoopInstance(def, anchor));
	}

	/** 停止指定锚点上的指定定义实例;不存在时 no-op。 */
	public static void stop(Object anchor, String defId) {
		if (anchor == null || defId == null) {
			return;
		}
		INSTANCES.remove(new LoopKey(anchor, defId));
	}

	/** 停止指定锚点上的全部循环实例（如：绑定方块被解绑/移除）。 */
	public static void stopAll(Object anchor) {
		if (anchor == null) {
			return;
		}
		INSTANCES.keySet().removeIf(key -> key.anchor().equals(anchor));
	}

	/** 指定锚点 + 定义是否在运行。 */
	public static boolean isRunning(Object anchor, String defId) {
		return anchor != null && defId != null
				&& INSTANCES.containsKey(new LoopKey(anchor, defId));
	}

	/** 当前活动实例数。 */
	public static int activeCount() {
		return INSTANCES.size();
	}

	/** 全部活动实例的只读快照（按 defId + anchor 排序,输出稳定）。 */
	public static List<LoopStatus> status() {
		List<LoopStatus> out = new ArrayList<>();
		for (LoopInstance inst : INSTANCES.values()) {
			out.add(inst.snapshot());
		}
		out.sort(Comparator.comparing(LoopStatus::defId)
				.thenComparing(s -> String.valueOf(s.anchor())));
		return out;
	}

	/** 清空全部实例（服务端停止时调用）。 */
	public static void clear() {
		INSTANCES.clear();
	}

	// ------------------------------------------------------------------
	// tick 驱动（服务端线程调用;每 tick 每个实例推进一个阶段）
	// ------------------------------------------------------------------

	/** 推进所有实例一个阶段（由接线层在 {@code ServerTickEvents.END_SERVER_TICK} 调用）。 */
	public static void tick(long gameTick) {
		for (LoopInstance inst : List.copyOf(INSTANCES.values())) {
			step(inst, gameTick);
		}
	}

	private static void step(LoopInstance inst, long tick) {
		LoopContext ctx = new LoopContext(inst.anchor, tick, inst.iteration, inst.state);
		switch (inst.phase) {
			case WAITING -> stepWaiting(inst, ctx, tick);
			case EXECUTING -> stepExecuting(inst, ctx);
			case MONITORING -> stepMonitoring(inst, ctx);
		}
	}

	/** WAITING：评估触发条件（受间隔门控）。 */
	private static void stepWaiting(LoopInstance inst, LoopContext ctx, long tick) {
		if (tick < inst.nextCheckTick) {
			return; // interval 间隔未到
		}
		boolean pass;
		try {
			pass = inst.def.trigger().check(ctx);
		} catch (Exception e) {
			OpenCraftMod.LOGGER.warn("[OpenCraft] 循环 {}（{}）触发条件异常,本轮按不通过处理: {}",
					inst.def.id(), inst.anchor, e.toString());
			pass = false;
			inst.consecutiveErrors++;
			if (inst.consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
				OpenCraftMod.LOGGER.warn("[OpenCraft] 循环 {}（{}）触发条件连续异常 {} 次,停止实例",
						inst.def.id(), inst.anchor, inst.consecutiveErrors);
				INSTANCES.remove(inst.key());
				return;
			}
		}
		inst.nextCheckTick = tick + inst.def.intervalTicks();
		if (pass) {
			inst.consecutiveErrors = 0;
			inst.phase = LoopPhase.EXECUTING;
		}
		// 不通过：保持 WAITING,等待下一个间隔
	}

	/** EXECUTING：执行事件一次。 */
	private static void stepExecuting(LoopInstance inst, LoopContext ctx) {
		try {
			inst.def.event().execute(ctx);
		} catch (Exception e) {
			OpenCraftMod.LOGGER.warn("[OpenCraft] 循环 {}（{}）执行事件异常,停止实例: {}",
					inst.def.id(), inst.anchor, e.toString());
			INSTANCES.remove(inst.key());
			return;
		}
		inst.iteration++;
		inst.phase = LoopPhase.MONITORING;
	}

	/** MONITORING：执行监测函数,决定续环或结束。 */
	private static void stepMonitoring(LoopInstance inst, LoopContext ctx) {
		LoopVerdict verdict;
		try {
			verdict = inst.def.monitor().monitor(ctx);
		} catch (Exception e) {
			OpenCraftMod.LOGGER.warn("[OpenCraft] 循环 {}（{}）监测函数异常,停止实例: {}",
					inst.def.id(), inst.anchor, e.toString());
			INSTANCES.remove(inst.key());
			return;
		}
		if (verdict == LoopVerdict.CONTINUE || inst.def.persistent()) {
			// CONTINUE：回到触发条件;persistent 的 STOP：结束本轮、回到等待继续监视
			inst.phase = LoopPhase.WAITING;
		} else {
			// 一次性循环的 STOP：移除实例
			INSTANCES.remove(inst.key());
			return;
		}
		if (inst.def.maxIterations() > 0 && inst.iteration >= inst.def.maxIterations()) {
			// 达到事件执行次数上限：正常停止
			INSTANCES.remove(inst.key());
		}
	}

	// ------------------------------------------------------------------
	// 内部：实例与键
	// ------------------------------------------------------------------

	/** 实例键：同一锚点可同时运行多个不同定义的循环。 */
	private record LoopKey(Object anchor, String defId) {
	}

	/** 一个活动循环实例的运行时状态。 */
	private static final class LoopInstance {
		final LoopDefinition def;
		final Object anchor;
		/** 实例级持久共享状态（跨 tick 复用同一个 map,见 {@link LoopContext#state()}）。 */
		final Map<String, Object> state = new HashMap<>();
		LoopPhase phase = LoopPhase.WAITING;
		long iteration;
		long nextCheckTick;
		int consecutiveErrors;

		LoopInstance(LoopDefinition def, Object anchor) {
			this.def = def;
			this.anchor = anchor;
		}

		LoopKey key() {
			return new LoopKey(anchor, def.id());
		}

		LoopStatus snapshot() {
			return new LoopStatus(def.id(), anchor, phase, iteration, nextCheckTick);
		}
	}
}