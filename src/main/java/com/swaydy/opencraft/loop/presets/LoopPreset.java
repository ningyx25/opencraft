package com.swaydy.opencraft.loop.presets;

import com.swaydy.opencraft.loop.LoopCondition;
import com.swaydy.opencraft.loop.LoopDefinition;
import com.swaydy.opencraft.loop.LoopEvent;
import com.swaydy.opencraft.loop.LoopMonitor;
import com.swaydy.opencraft.loop.LoopVerdict;

/**
 * 内置循环事件预设的基类（SPI）：预设 = 一个「触发条件 → 执行事件 → 监测条件 →（继续）→ 触发条件 → …」
 * 循环事件的定义单元（三个组成部分 + 显示信息 + 运行参数），由 {@link #definition()} 组装成
 * 不可变的 {@link LoopDefinition} 供注册表/引擎消费。
 *
 * <p>与 {@code plugins/presets/AssistantPlugin} / {@code agent/presets/BaseAgent} 同一套管理思路：
 * 预设类（如 {@code HealAuraLoop}）继承本基类、以覆写访问器的方式声明自身属性（触发条件/事件/
 * 监测函数/运行参数），内置预设集中在 {@code loop/presets/} 子包，方便扩展新循环事件。
 * 由 {@code LoopModule.init()} 注册进 {@link com.swaydy.opencraft.loop.LoopRegistry};
 * 框架（{@code loop/} 根包的纯 Java 引擎）与内置实现分离。
 *
 * <p><b>实现约定</b>：
 * <ul>
 * <li>{@link #id()} / {@link #trigger()} / {@link #event()} / {@link #monitor()} 必填——
 *     循环三组成部分（Minecraft 对象经闭包捕获：{@code LoopModule.server()} 取实时服务端、
 *     {@code ctx.anchor()} 取实例锚点）;</li>
 * <li>显示名/说明与运行参数可选,带默认值（interval 20 tick、不限制迭代、非 persistent）;</li>
 * <li>服务端线程同步执行,三部分必须快速返回、不得阻塞;禁止抛异常（引擎有守卫,但抛了会停实例）。</li>
 * </ul>
 */
public abstract class LoopPreset {
	/** 循环事件唯一 id（LoopRegistry 键）,如 "heal_aura"。 */
	public abstract String id();

	/** 显示名（{@code /opencraft loop status} 输出用）;可为 null。 */
	public String displayName() {
		return null;
	}

	/** 一句话说明（{@code /opencraft loop status} 输出用）;可为 null。 */
	public String description() {
		return null;
	}

	/** 触发条件：成立才进入执行事件阶段（纯谓词,无副作用）。 */
	public abstract LoopCondition trigger();

	/** 执行事件：触发条件成立后执行的动作（成功即一次有效迭代）。 */
	public abstract LoopEvent event();

	/**
	 * 监测函数：事件执行后评估监测条件,返回 {@link LoopVerdict#CONTINUE}（回触发条件继续下一轮）
	 * 或 {@link LoopVerdict#STOP}（结束本轮）。
	 */
	public abstract LoopMonitor monitor();

	/** 两次触发条件评估的最小 tick 间隔（默认 20 = 1 秒;1 = 每 tick 一循环）。 */
	public int intervalTicks() {
		return 20;
	}

	/** 事件最大执行次数上限（默认 0 = 不限;达到后正常停止实例）。 */
	public int maxIterations() {
		return 0;
	}

	/**
	 * monitor 返回 STOP 时的语义：false（默认）= 移除实例（一次性任务循环）;
	 * true = 只结束本轮、回等待状态继续监视触发条件（守护型循环,如治疗光环）。
	 */
	public boolean persistent() {
		return false;
	}

	/** 组装本预设的 {@link LoopDefinition}（供 {@link com.swaydy.opencraft.loop.LoopRegistry} 注册）。 */
	public final LoopDefinition definition() {
		return LoopDefinition.builder(id())
				.displayName(displayName())
				.description(description())
				.trigger(trigger())
				.event(event())
				.monitor(monitor())
				.intervalTicks(intervalTicks())
				.maxIterations(maxIterations())
				.persistent(persistent())
				.build();
	}
}