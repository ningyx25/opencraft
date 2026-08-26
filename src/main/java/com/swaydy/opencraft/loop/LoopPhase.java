package com.swaydy.opencraft.loop;

/**
 * 循环实例的三阶段状态机阶段。
 *
 * <p>每个服务端 tick 推进一个阶段（状态不跨 tick 跳跃）：
 * <ol>
 * <li>{@link #WAITING}——评估触发条件（受 interval 间隔门控）;
 *     通过 → 进入 {@link #EXECUTING};不通过 → 保持 WAITING 等待下次;</li>
 * <li>{@link #EXECUTING}——执行事件一次;执行后 → 进入 {@link #MONITORING};</li>
 * <li>{@link #MONITORING}——执行监测函数;CONTINUE → 回到 WAITING;
 *     STOP → 停止实例（persistent 实例回到 WAITING 继续监视）。</li>
 * </ol>
 *
 * <p>纯 Java、无 Minecraft 依赖。
 */
public enum LoopPhase {
	/** 等待触发条件评估（受 interval 门控,不到 tick 不评估）。 */
	WAITING,
	/** 执行事件（一次）。 */
	EXECUTING,
	/** 执行监测函数。 */
	MONITORING
}