package com.swaydy.opencraft.entity;

import com.swaydy.opencraft.ai.AiBlockConfig;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;

/**
 * 助手跟随主人的 AI 目标：
 * - 距离超过 followDistance 时走向主人；
 * - 距离小于 stopDistance 时停下；
 * - 距离超过 teleportDistance 时尝试直接瞬移到主人身边；
 * - 主人不在线 / 不在同一维度 / 距离超过 maxDistance 时放弃（原地待命）。
 */
public class FollowAssistantOwnerGoal extends Goal {
	private final AiAssistantEntity assistant;
	private int recalcPathTicks = 0;

	public FollowAssistantOwnerGoal(AiAssistantEntity assistant) {
		this.assistant = assistant;
		this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	@Override
	public boolean canUse() {
		Player owner = assistant.getOwner();
		if (owner == null || !assistant.isFollowing()) {
			return false;
		}
		double maxDist = assistant.getConfig().maxDistance;
		return assistant.distanceToSqr(owner) <= maxDist * maxDist;
	}

	@Override
	public boolean canContinueToUse() {
		Player owner = assistant.getOwner();
		if (owner == null || !assistant.isFollowing()) {
			return false;
		}
		AiBlockConfig config = assistant.getConfig();
		double stopDist = config.stopDistance;
		double maxDist = config.maxDistance;
		double distSq = assistant.distanceToSqr(owner);
		return distSq > stopDist * stopDist && distSq <= maxDist * maxDist;
	}

	@Override
	public void start() {
		this.recalcPathTicks = 0;
	}

	@Override
	public void stop() {
		assistant.getNavigation().stop();
	}

	@Override
	public void tick() {
		Player owner = assistant.getOwner();
		if (owner == null) {
			return;
		}
		assistant.getLookControl().setLookAt(owner, 30.0F, 30.0F);
		AiBlockConfig config = assistant.getConfig();
		double distSq = assistant.distanceToSqr(owner);
		double teleportDistSq = config.teleportDistance * config.teleportDistance;
		double followDistSq = config.followDistance * config.followDistance;

		if (distSq > teleportDistSq) {
			teleportNear(owner);
			return;
		}
		if (--this.recalcPathTicks <= 0) {
			this.recalcPathTicks = 10;
			if (distSq > followDistSq) {
				assistant.getNavigation().moveTo(owner, config.speed);
			} else {
				assistant.getNavigation().stop();
			}
		}
	}

	/** 尝试在主人附近找一个安全位置瞬移过去；失败则直接瞬移到主人坐标。 */
	private void teleportNear(Player owner) {
		for (int i = 0; i < 8; i++) {
			double dx = assistant.getRandom().nextDouble() * 6.0 - 3.0;
			double dz = assistant.getRandom().nextDouble() * 6.0 - 3.0;
			if (assistant.randomTeleport(owner.getX() + dx, owner.getY(), owner.getZ() + dz, false)) {
				return;
			}
		}
		assistant.teleportTo(owner.getX() + 0.5, owner.getY(), owner.getZ() + 0.5);
	}
}
