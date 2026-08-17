package com.swaydy.opencraft.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.PathNavigation;

/**
 * 移动到指定方块旁的任务。
 *
 * 只下达寻路指令：助手走向目标方块；到达（距离 ≤ 1.5 格）即成功，
 * 超时（默认 30 秒）或路径不存在视为失败。模型后续用 look_around 观察结果。
 */
public class MoveToBlockTask extends AssistantTask {
	private static final double ARRIVE_DIST = 1.5;
	private static final int TIMEOUT_TICKS = 600; // 30s

	private final BlockPos target;
	private final long deadlineTick;
	private boolean done = false;
	private boolean failed = false;
	private int navRecalc = 0;

	public MoveToBlockTask(AiAssistantEntity assistant, BlockPos target) {
		super(assistant);
		this.target = target.immutable();
		this.deadlineTick = assistant.tickCount + TIMEOUT_TICKS;
	}

	@Override
	public void start() {
		this.navRecalc = 0;
	}

	@Override
	public void tick() {
		if (done || failed) {
			return;
		}
		if (assistant.tickCount > deadlineTick) {
			failed = true;
			assistant.getNavigation().stop();
			return;
		}
		// 到达判定：水平距离 ≤ ARRIVE_DIST（忽略 y，允许站在不同高度）
		double dx = assistant.getX() - (target.getX() + 0.5);
		double dz = assistant.getZ() - (target.getZ() + 0.5);
		if (dx * dx + dz * dz <= ARRIVE_DIST * ARRIVE_DIST) {
			done = true;
			assistant.getNavigation().stop();
			return;
		}
		if (--navRecalc <= 0) {
			navRecalc = 10;
			PathNavigation nav = assistant.getNavigation();
			// 目标方块上方的空气格作为寻路终点（站在方块旁而不是方块里）
			boolean moved = nav.moveTo(target.getX() + 0.5, target.getY() + 0.5,
					target.getZ() + 0.5, assistant.getConfig().speed);
			if (!moved && !nav.isInProgress()) {
				failed = true;
			}
		}
	}

	@Override
	public boolean isDone() {
		return done;
	}

	@Override
	public boolean isFailed() {
		return failed;
	}

	@Override
	public String describe() {
		return "正在前往 (" + target.getX() + "," + target.getY() + "," + target.getZ() + ")";
	}

	public BlockPos getTarget() {
		return target;
	}
}