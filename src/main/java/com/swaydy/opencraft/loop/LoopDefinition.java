package com.swaydy.opencraft.loop;

/**
 * 一个循环事件的定义：由「触发条件」「执行事件」「监测函数」三个组成部分 +
 * 运行参数组成。运行时以 {@link LoopEngine#start(LoopDefinition, Object)} 挂到某个锚点
 * 上成为活动实例。
 *
 * <p><b>循环语义</b>（{@code 触发条件 → 执行事件 → 监测条件 → 触发条件 → …}）：
 * <ol>
 * <li><b>触发条件</b> {@code trigger}——每 {@code intervalTicks} tick 评估一次;
 *     通过才进入执行阶段,不通过则跳过本轮继续等待;</li>
 * <li><b>执行事件</b> {@code event}——触发条件成立后执行一次;</li>
 * <li><b>监测函数</b> {@code monitor}——事件后评估监测条件：
 *     {@link LoopVerdict#CONTINUE} 回到触发条件;{@link LoopVerdict#STOP} 结束本轮——
 *     {@code persistent=false}（默认）时移除实例（一次性任务循环）;
 *     {@code persistent=true} 时实例回到等待状态继续监视触发条件（守护型循环,如治疗光环）。</li>
 * </ol>
 *
 * @param id            唯一标识（小写 kebab-case,如 {@code "heal_aura"}）
 * @param displayName   显示名（状态输出用）
 * @param description   一句话说明（状态输出/调试用）
 * @param trigger       触发条件（非 null）
 * @param event         执行事件（非 null）
 * @param monitor       监测函数（非 null）
 * @param intervalTicks 最小两次触发条件评估之间的 tick 间隔（≥ 1;1 = 每 tick 一循环）
 * @param maxIterations 事件最大执行次数上限（0 = 不限;达到后正常停止实例）
 * @param persistent    监测函数返回 STOP 时：false = 移除实例;true = 回到等待继续监视
 */
public record LoopDefinition(String id, String displayName, String description,
                             LoopCondition trigger, LoopEvent event, LoopMonitor monitor,
                             int intervalTicks, int maxIterations, boolean persistent) {

	/** 构造校验：非法参数抛 {@link IllegalArgumentException}。 */
	public LoopDefinition {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("LoopDefinition.id 不能为空");
		}
		if (trigger == null || event == null || monitor == null) {
			throw new IllegalArgumentException(
					"LoopDefinition 必须同时提供 trigger / event / monitor: " + id);
		}
		if (intervalTicks < 1) {
			throw new IllegalArgumentException("LoopDefinition.intervalTicks 必须 ≥ 1: " + id);
		}
		if (maxIterations < 0) {
			throw new IllegalArgumentException("LoopDefinition.maxIterations 必须 ≥ 0: " + id);
		}
	}

	/**
	 * 构造器。默认：intervalTicks = 20（1 秒一轮）、maxIterations = 0（不限）、
	 * persistent = false（一次性循环）。
	 */
	public static Builder builder(String id) {
		return new Builder(id);
	}

	/** {@link LoopDefinition} 的构建器。 */
	public static final class Builder {
		private final String id;
		private String displayName;
		private String description;
		private LoopCondition trigger;
		private LoopEvent event;
		private LoopMonitor monitor;
		private int intervalTicks = 20;
		private int maxIterations;
		private boolean persistent;

		Builder(String id) {
			this.id = id;
		}

		public Builder displayName(String displayName) {
			this.displayName = displayName;
			return this;
		}

		public Builder description(String description) {
			this.description = description;
			return this;
		}

		public Builder trigger(LoopCondition trigger) {
			this.trigger = trigger;
			return this;
		}

		public Builder event(LoopEvent event) {
			this.event = event;
			return this;
		}

		public Builder monitor(LoopMonitor monitor) {
			this.monitor = monitor;
			return this;
		}

		public Builder intervalTicks(int intervalTicks) {
			this.intervalTicks = intervalTicks;
			return this;
		}

		public Builder maxIterations(int maxIterations) {
			this.maxIterations = maxIterations;
			return this;
		}

		public Builder persistent(boolean persistent) {
			this.persistent = persistent;
			return this;
		}

		public LoopDefinition build() {
			return new LoopDefinition(id, displayName, description,
					trigger, event, monitor, intervalTicks, maxIterations, persistent);
		}
	}
}