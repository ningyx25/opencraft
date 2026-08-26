package com.swaydy.opencraft.loop;

/**
 * 循环事件中「监测函数」的裁决结果：
 * <ul>
 * <li>{@link #CONTINUE}——监测条件仍成立,回到触发条件继续下一轮
 *     （触发条件 → 执行事件 → 监测条件 → 触发条件 → …）;</li>
 * <li>{@link #STOP}——监测条件不成立,结束本轮：persistent 循环回到等待状态继续监视,
 *     一次性循环则移除实例。</li>
 * </ul>
 *
 * <p>纯 Java、无 Minecraft 依赖。
 */
public enum LoopVerdict {
	/** 监测条件成立：回到触发条件,继续下一轮循环。 */
	CONTINUE,
	/** 监测条件不成立：结束本轮（一次性循环移除实例；persistent 循环回到等待监视）。 */
	STOP
}
