package com.swaydy.opencraft.entity;

import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * 助手任务的“宿主 Goal”：在实体 goalSelector 优先级 0 常驻注册，
 * 代理执行当前任务（{@link AiAssistantEntity#getCurrentTask()}）。
 *
 * - canUse/canContinueToUse：有未终结的任务且助手活着；
 * - tick：驱动当前任务的 tick；任务终结时通过回调让实体清空并通知插件/模型。
 * - 由于优先级 0（与 FloatGoal 同级），任务活跃时会压制优先级更低的跟随/散步 Goal。
 *
 * 任务完成/失败后的清理与通知（停止导航、向 owner 广播、可选的模型可见状态）
 * 由实体 setCurrentTask 的收尾与 tick 末尾的回调处理。
 */
public class TaskHostGoal extends Goal {
	private final AiAssistantEntity assistant;

	public TaskHostGoal(AiAssistantEntity assistant) {
		this.assistant = assistant;
		this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		AssistantTask task = assistant.getCurrentTask();
		return task != null && !task.isFinished() && !assistant.isRemoved();
	}

	@Override
	public boolean canContinueToUse() {
		AssistantTask task = assistant.getCurrentTask();
		return task != null && !task.isFinished() && !assistant.isRemoved();
	}

	@Override
	public boolean isInterruptable() {
		return true;
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void start() {
		AssistantTask task = assistant.getCurrentTask();
		if (task != null) {
			task.start();
		}
	}

	@Override
	public void tick() {
		AssistantTask task = assistant.getCurrentTask();
		if (task == null) {
			return;
		}
		task.tick();
		// 任务终结：清理当前任务（通知实体，实体再通知 owner/插件）
		if (task.isFinished()) {
			assistant.completeCurrentTask();
		}
	}

	@Override
	public void stop() {
		// 任务被更高优先级 Goal 抢占（罕见）：不终结任务，只是暂停驱动
	}
}