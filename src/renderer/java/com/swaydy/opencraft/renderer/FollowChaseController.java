package com.swaydy.opencraft.renderer;

import com.replaymod.replay.camera.CameraController;
import com.replaymod.replay.camera.CameraEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * 回放相机控制器：第三人称追尾。每 tick 找到目标玩家（默认第一个非 {@code E2E_} 前缀的
 * 玩家——即录制里的 AI 助手小智；也可用 {@code -Dopencraft.render.follow=<名字>} 指定），
 * 把 ReplayMod 相机放到目标身后上方、朝向与目标一致，从而在画面里看到助手全身动作。
 *
 * <p>不注册任何输入响应（无头渲染），{@code increaseSpeed/decreaseSpeed} 为空。</p>
 */
public class FollowChaseController implements CameraController {
	/** 相机与目标的水平距离（格）。 */
	private static final double DISTANCE = 4.0;
	/** 相机相对目标脚部的高度（格）。 */
	private static final double HEIGHT = 2.2;
	/** 相机俯视角（度）。 */
	private static final float PITCH = 16f;

	private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("opencraft-renderer");

	private final CameraEntity camera;
	private final String followName;
	private int updates;
	private boolean loggedTargets;

	public FollowChaseController(CameraEntity camera, String followName) {
		this.camera = camera;
		this.followName = followName == null ? "" : followName.trim();
	}

	@Override
	public void update(float partialTicks) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null) {
			return;
		}
		if (!loggedTargets) {
			loggedTargets = true;
			java.util.List<String> names = new java.util.ArrayList<>();
			for (AbstractClientPlayer pl : level.players()) {
				names.add("tab:" + pl.getName().getString() + "@(" + (int) pl.getX() + "," + (int) pl.getY() + "," + (int) pl.getZ() + ")");
			}
			int playerEntities = 0;
			for (net.minecraft.world.entity.Entity e : playerEntitiesNear(level)) {
				if (e != camera) {
					playerEntities++;
					if (names.size() < 12) {
						names.add("entity:" + e.getName().getString() + "@(" + (int) e.getX() + "," + (int) e.getY() + "," + (int) e.getZ() + ")");
					}
				}
			}
			LOGGER.info("[renderer][cam] players={} playerEntitiesExclCam={} followName='{}' cameraIsPlayer={}",
					names, playerEntities, followName, mc.player == camera);
		}
		LivingEntity target = findTarget(level);
		updates++;
		if (updates == 1 || updates % 600 == 1) {
			LOGGER.info("[renderer][cam] update#{} target={} cam=({},{},{}) totalEntities={}",
					updates,
					target == null ? "null"
							: target.getName().getString() + "@(" + (int) target.getX() + "," + (int) target.getY() + "," + (int) target.getZ() + ")",
					(int) camera.getX(), (int) camera.getY(), (int) camera.getZ(),
					level.getEntityCount());
		}
		if (target == null) {
			return;
		}
		// 目标朝向（用身体 yaw，比头部 yaw 更稳）；MC 里 yaw 0=朝南(+z)，
		// 朝向向量 = (-sin(yaw), cos(yaw))，相机放在 -朝向 * 距离（身后）。
		float yaw = target.yBodyRot;
		double rad = Math.toRadians(yaw);
		double facingX = -Math.sin(rad);
		double facingZ = Math.cos(rad);
		double cx = target.getX() - facingX * DISTANCE;
		double cz = target.getZ() - facingZ * DISTANCE;
		double cy = target.getY() + HEIGHT;
		camera.setCameraPosition(cx, cy, cz);
		camera.setCameraRotation(yaw, PITCH, 0f);
	}

	private LivingEntity findTarget(ClientLevel level) {
		LivingEntity fallback = null;
		// 1) tab 玩家列表
		for (AbstractClientPlayer player : level.players()) {
			if (player == Minecraft.getInstance().player) {
				continue; // 相机自身
			}
			LivingEntity t = consider(player);
			if (t == camera) {
				continue;
			}
			if (t != null) {
				return t;
			}
			if (fallback == null && !player.getName().getString().startsWith("E2E_")) {
				fallback = player;
			}
		}
		// 2) 相机附近大范围里的玩家实体（回放里远端玩家可能不在 tab 列表，只作为实体存在）
		for (net.minecraft.world.entity.Entity e : playerEntitiesNear(level)) {
			if (e == camera || e == Minecraft.getInstance().player) {
				continue;
			}
			String name = e.getName().getString();
			if (!followName.isEmpty()) {
				if (followName.equals(name)) {
					return (LivingEntity) e;
				}
				continue;
			}
			if (name.startsWith("E2E_")) {
				continue;
			}
			if (fallback == null) {
				fallback = (LivingEntity) e;
			}
		}
		return fallback;
	}

	/** 相机周围 ±512 格内的玩家实体（含 tab 玩家，远端玩家若只是实体也能找到）。 */
	@SuppressWarnings("unchecked")
	private java.util.List<net.minecraft.world.entity.Entity> playerEntitiesNear(ClientLevel level) {
		net.minecraft.world.phys.AABB box = camera.getBoundingBox().inflate(512.0);
		return (java.util.List<net.minecraft.world.entity.Entity>) (java.util.List<?>) level.getEntitiesOfClass(
				net.minecraft.world.entity.player.Player.class, box);
	}

	/** 按 followName 精确匹配返回目标；未指定名字时跳过 E2E_ 假玩家并返回第一个真玩家。 */
	private LivingEntity consider(LivingEntity player) {
		String name = player.getName().getString();
		if (!followName.isEmpty()) {
			return followName.equals(name) ? player : null;
		}
		return name.startsWith("E2E_") ? null : player;
	}

	@Override
	public void increaseSpeed() {
	}

	@Override
	public void decreaseSpeed() {
	}
}
