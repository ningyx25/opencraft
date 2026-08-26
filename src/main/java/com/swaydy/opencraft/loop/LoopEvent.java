package com.swaydy.opencraft.loop;

/**
 * 循环事件的「执行事件」：触发条件成立后执行的动作。
 *
 * <p>约定（由 {@link LoopEngine} 强制）：
 * <ul>
 * <li>在服务端 tick 线程上同步执行,必须快速返回、不得阻塞;</li>
 * <li>允许抛出异常——引擎会停止该实例并告警（不把异常抛给调用方）;</li>
 * <li>执行成功即视为一次有效迭代（{@code LoopContext.iteration} 递增）。</li>
 * </ul>
 *
 * <p>纯 Java、无 Minecraft 依赖。需要 Minecraft 对象时在接线层以闭包方式捕获。
 */
@FunctionalInterface
public interface LoopEvent {

	/** 执行一次事件。 */
	void execute(LoopContext ctx);
}
