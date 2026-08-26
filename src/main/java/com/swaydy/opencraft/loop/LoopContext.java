package com.swaydy.opencraft.loop;

import java.util.Map;

/**
 * 循环事件的执行上下文：一次循环实例在单轮推进中传给
 * {@link LoopCondition} / {@link LoopEvent} / {@link LoopMonitor} 的信息与共享状态。
 *
 * <p><b>状态共享</b>：{@code state} 是<b>实例级持久</b>的 map（引擎为每个实例创建一个,
 * 跨所有 tick 复用同一个）,供条件/事件在轮次之间传递数据
 * （如"上一轮计数""目标是否已达成"）,与 agentic loop 的 LoopContext 分工一致。
 *
 * <p><b>与 Minecraft 解耦</b>：本类不持有任何 Minecraft 引用,保持纯 Java 可单测;
 * 接线层把 Minecraft 对象放进条件/事件的闭包（参考 {@code loop.LoopModule#server()}）。
 *
 * @param anchor      实例锚点（引擎键控用,如绑定方块的 {@code GlobalPos};单测里可为任意对象）
 * @param tick        当前服务端 tick 数
 * @param iteration   该实例已成功执行事件的次数（本轮开始前计数）
 * @param state       实例级持久共享状态（可修改）
 */
public record LoopContext(Object anchor, long tick, long iteration, Map<String, Object> state) {

	/** 该实例的共享状态（跨 tick 持久;写入后后续轮次可读）。 */
	public Map<String, Object> state() {
		return state;
	}
}
