package com.swaydy.opencraft.loop;

/**
 * 循环事件的「触发条件」：一个纯谓词,决定本轮是否执行事件。
 *
 * <p>约定（由 {@link LoopEngine} 强制）：
 * <ul>
 * <li>应为<b>纯函数</b>——无副作用、幂等、便宜（每轮最多调用一次,在服务端 tick 线程上）;</li>
 * <li>禁止抛出异常——引擎会把异常按 false 处理并累计错误,连续超阈值自动停止实例;</li>
 * <li>返回 true → 引擎进入执行事件阶段；false → 本轮跳过,按间隔等待下次评估。</li>
 * </ul>
 *
 * <p>纯 Java、无 Minecraft 依赖。需要 Minecraft 对象（服务端/玩家/方块）时,
 * 在接线层以闭包方式捕获（参考 {@code loop.LoopModule} 的 server 引用）。
 */
@FunctionalInterface
public interface LoopCondition {

	/** 评估触发条件。true = 条件成立,进入执行事件阶段。 */
	boolean check(LoopContext ctx);
}
