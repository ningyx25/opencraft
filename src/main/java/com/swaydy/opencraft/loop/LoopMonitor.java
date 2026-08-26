package com.swaydy.opencraft.loop;

/**
 * 循环事件的「监测函数」：在事件执行后评估监测条件,决定是否继续循环。
 *
 * <p>约定（由 {@link LoopEngine} 强制）：
 * <ul>
 * <li>在服务端 tick 线程上同步执行,必须快速返回、不得阻塞;</li>
 * <li>{@link LoopVerdict#CONTINUE}——回到触发条件,继续下一轮;</li>
 * <li>{@link LoopVerdict#STOP}——结束本轮（见 {@link LoopDefinition#persistent()}）;</li>
 * <li>抛出异常 → 引擎按 STOP 处理并停止实例。</li>
 * </ul>
 *
 * <p>纯 Java、无 Minecraft 依赖。需要 Minecraft 对象时在接线层以闭包方式捕获。
 */
@FunctionalInterface
public interface LoopMonitor {

	/** 事件执行后,评估监测条件并返回裁决。 */
	LoopVerdict monitor(LoopContext ctx);
}