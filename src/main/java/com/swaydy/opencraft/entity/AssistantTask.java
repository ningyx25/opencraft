package com.swaydy.opencraft.entity;

import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * 助手可执行的异步任务基类。
 *
 * 任务以【高优先级 Goal】的形式挂到实体 goalSelector（优先级 0，与 FloatGoal 同级），
 * 任务活跃时压制优先级更低的散步 Goal；{@link #tick()} 由 goalSelector 每 tick 驱动。
 * 助手实体负责任务生命周期：收到新任务时取消旧任务，任务 isFinished 时从 selector 移除。
 *
 * 任务只【下达指令】（寻路/挥动/破坏），立即返回；模型通过后续 {@code look_around}
 * 观察任务结果。done/failed 由任务自行判定。
 */
public abstract class AssistantTask extends Goal {
	protected final AiAssistantEntity assistant;

	protected AssistantTask(AiAssistantEntity assistant) {
		this.assistant = assistant;
		this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		return !isFinished();
	}

	@Override
	public boolean canContinueToUse() {
		return !isFinished();
	}

	@Override
	public boolean isInterruptable() {
		return true;
	}

	/** 任务需要每 tick 驱动（寻路/破坏判定不及时会显得迟钝）。 */
	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	/** 任务是否已完成（成功）。 */
	public abstract boolean isDone();

	/** 任务是否已失败（超时/目标不可达/被取消）。 */
	public abstract boolean isFailed();

	/** 任务的中文描述（供 look_around / 状态显示）。 */
	public abstract String describe();

	/** 任务是否已终结（成功或失败）。 */
	public final boolean isFinished() {
		return isDone() || isFailed();
	}
}