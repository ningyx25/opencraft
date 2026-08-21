package com.swaydy.opencraft.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;

/**
 * 攻击指定实体的任务：靠近目标并持续近战攻击，目标死亡/消失即完成。
 */
public class AttackTask extends AssistantTask {
	private static final double ATTACK_RANGE = 1.8; // 近战攻击距离
	private static final int TIMEOUT_TICKS = 600; // 30s

	private final ServerLevel level;
	private final LivingEntity target;
	private final long deadlineTick;
	private boolean done = false;
	private boolean failed = false;
	private int pathRecalc = 0;
	private int attackTimer = 0;

	public AttackTask(AiAssistantEntity assistant, ServerLevel level, LivingEntity target) {
		super(assistant);
		this.level = level;
		this.target = target;
		this.deadlineTick = assistant.tickCount + TIMEOUT_TICKS;
	}

	@Override
	public void start() {
		this.pathRecalc = 0;
		this.attackTimer = 0;
	}

	@Override
	public void tick() {
		if (done || failed) {
			return;
		}
		// 目标消失/死亡/被移除 → 完成
		if (target == null || !target.isAlive() || target.isRemoved()) {
			done = true;
			assistant.getNavigation().stop();
			return;
		}
		if (assistant.tickCount > deadlineTick) {
			failed = true;
			assistant.getNavigation().stop();
			return;
		}
		// 看向目标
		assistant.getLookControl().setLookAt(target, 30.0F, 30.0F);
		double distSq = assistant.distanceToSqr(target);
		if (distSq > ATTACK_RANGE * ATTACK_RANGE) {
			if (--pathRecalc <= 0) {
				pathRecalc = 10;
				assistant.getNavigation().moveTo(target, assistant.getConfig().speed);
			}
			return;
		}
		// 就位：近战攻击
		if (--attackTimer <= 0) {
			attackTimer = 20; // 每秒攻击一次
			assistant.swing(InteractionHand.MAIN_HAND);
			assistant.doHurtTarget(level, target);
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
		return "Attacking " + (target == null ? "target" : target.getName().getString());
	}
}