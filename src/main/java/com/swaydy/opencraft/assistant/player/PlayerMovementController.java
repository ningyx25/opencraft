package com.swaydy.opencraft.assistant.player;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 玩家形态助手的移动控制器（bot 式移动）。
 *
 * ServerPlayer 在服务端不自动应用输入/重力——服务器信任客户端的移动包（handleMovePlayer），
 * 因此这里直接驱动位置：每 tick 用 {@code move(MoverType.PLAYER, delta)} 带碰撞地移动，
 * 自己施加重力/跳跃（跳过 1 格障碍），长时间卡住直接传送（与实体版助手的传送回退一致）。
 *
 * <p><b>关键细节</b>：
 * - <b>着地判定不依赖 {@code Entity.onGround}</b>：bot 没有客户端移动包，纯水平 move()
 *   不会刷新 onGround 标志（它会停留在上次落地时的 true）——若用它门控重力，
 *   走出平台边缘后 bot 会“浮空”继续走、Y 永不变。因此每 tick 用脚底下方一小段
 *   水平切片与方块碰撞（{@link #hasGroundBelow}）实时判定是否真的着地；
 * - <b>移动时朝向目标</b>：同步设置 yRot/yHeadRot/yBodyRot（头/身/脚），
 *   用 {@link Mth#rotateTowards} 平滑转向，bot 走路才像正常玩家而不是侧滑；
 * - 跟随（follow）与手动指令（goto）共用本控制器：手动指令置 {@code manual=true}，
 *   跟随逻辑不会覆盖手动目标；{@link #stop()} 清除全部。
 */
public final class PlayerMovementController {
	/** 每 tick 最大水平步进（约 0.21 格 × speed，接近玩家的行走速度）。 */
	private static final double MAX_STEP = 0.21;
	/** 重力加速度（每 tick）。 */
	private static final double GRAVITY = 0.08;
	/** 最大下落速度（每 tick）。 */
	private static final double MAX_FALL_SPEED = -0.6;
	/** 跳跃初速度。 */
	private static final double JUMP_SPEED = 0.42;
	/** 卡住多久（tick）后传送回退。 */
	private static final int STUCK_TELEPORT_TICKS = 60;
	/** 每 tick 最大转向角度（度；20tps 下约 300°/s，平滑不突兀）。 */
	private static final float MAX_YAW_STEP = 15.0F;
	/**
	 * 脚底“着地切片”向下探的深度（格）。**必须极薄**：切片是“半空中就算着地”的判定——
	 * 太厚（如 0.06）会在 bot 还差几厘米落地时就判为着地、把重力清零，导致 bot 悬停
	 * 在离地几厘米的半空永远落不下去。2mm 厚 + 碰撞本身会把实体贴到方块顶面，
	 * 因此落地瞬间切片必然碰撞、贴地行走也必然碰撞。
	 */
	private static final double GROUND_SLICE_BELOW = 0.002;

	private Vec3 target;
	private double speed = 1.0;
	private boolean manual = false;
	private double vy = 0.0;
	private int stuckTicks = 0;
	private Runnable onArrived;
	private boolean arrivedFired = false;

	/** 下达移动目标（跟随用）。 */
	public void moveTo(Vec3 target, double speed) {
		moveTo(target, speed, false);
	}

	/** 下达移动目标；manual=true 表示是玩家/模型显式指令（跟随逻辑不会覆盖它）。 */
	public void moveTo(Vec3 target, double speed, boolean manual) {
		this.target = target;
		this.speed = Math.max(0.05, speed);
		this.manual = manual;
		this.arrivedFired = false;
	}

	/** 到达目标时执行一次的动作（如“走到方块旁再破坏”）；到达后自动清除。 */
	public void whenArrived(Runnable action) {
		this.onArrived = action;
	}

	public boolean isManual() {
		return manual;
	}

	public boolean isMoving() {
		return target != null;
	}

	/** 停止移动（同时清掉待执行的动作与手动标记）。 */
	public void stop() {
		this.target = null;
		this.onArrived = null;
		this.arrivedFired = false;
		this.manual = false;
	}

	/** 每 tick 驱动：重力/跳跃/前进/朝向/传送回退。在服务端线程调用。 */
	public void tick(AiAssistantPlayer player) {
		if (player == null || player.isRemoved()) {
			return;
		}
		Vec3 pos = player.position();
		boolean flying = player.getAbilities().flying;
		// 着地判定：实时查脚下方块，不依赖可能陈旧的 onGround 标志（见类注释）
		boolean grounded = hasGroundBelow(player);
		// 重力（飞行/着地时不累计；未着地时持续加速下落，封顶）
		if (!flying && !grounded) {
			vy -= GRAVITY;
			if (vy < MAX_FALL_SPEED) {
				vy = MAX_FALL_SPEED;
			}
		} else {
			vy = 0.0;
		}
		if (target == null) {
			// 空闲：仅处理重力，避免被推动后悬浮在半空
			if (!flying && Math.abs(vy) > 0.0001) {
				player.move(MoverType.PLAYER, new Vec3(0, vy, 0));
			}
			return;
		}
		double dx = target.x - pos.x;
		double dz = target.z - pos.z;
		double horiz = Math.sqrt(dx * dx + dz * dz);
		if (horiz <= 1.0) {
			// 到达：先执行“到达动作”（如挖掘），否则只停在这里
			if (onArrived != null && !arrivedFired) {
				arrivedFired = true;
				Runnable act = onArrived;
				onArrived = null;
				target = null;
				act.run();
			} else {
				target = null;
			}
			return;
		}
		double step = Math.min(MAX_STEP * speed, horiz);
		double mx = dx / horiz * step;
		double mz = dz / horiz * step;
		double my = flying ? 0.0 : vy;
		Vec3 before = player.position();
		player.move(MoverType.PLAYER, new Vec3(mx, my, mz));
		// 更新区块跟踪，让附近玩家看到助手平滑移动
		if (player.level() instanceof ServerLevel serverLevel) {
			serverLevel.getChunkSource().move(player);
		}
		// 朝向移动方向：头/身/脚一起平滑转向（像正常玩家走路而不是侧滑）
		faceTowards(player, dx, dz);
		Vec3 after = player.position();
		double moved = Math.hypot(after.x - before.x, after.z - before.z);
		if (moved < step * 0.25) {
			stuckTicks++;
			if (grounded && !flying) {
				vy = JUMP_SPEED; // 被挡：跳一下尝试越过 1 格障碍
			}
		} else {
			stuckTicks = 0;
		}
		if (stuckTicks > STUCK_TELEPORT_TICKS) {
			// 长时间卡住（悬崖/水/墙）：传送靠近目标，避免永远卡住
			player.teleportTo(target.x, target.y, target.z);
			stuckTicks = 0;
		}
	}

	/** 让玩家朝向 (dx, dz) 方向（yRot/yHeadRot/yBodyRot 同步平滑转向）。 */
	private static void faceTowards(AiAssistantPlayer player, double dx, double dz) {
		float yaw = (float) (Mth.atan2(-dx, dz) * (180.0 / Math.PI));
		float yawNext = turnTowards(player.getYRot(), yaw);
		player.setYRot(yawNext);
		player.setYHeadRot(turnTowards(player.getYHeadRot(), yaw));
		player.setYBodyRot(turnTowards(player.yBodyRot, yaw));
	}

	/** 把当前朝向朝目标朝向限幅转一步（每 tick 最多 MAX_YAW_STEP 度，处理 ±180° 环绕）。 */
	private static float turnTowards(float current, float target) {
		float diff = Mth.wrapDegrees(target - current);
		return Mth.wrapDegrees(current + Mth.clamp(diff, -MAX_YAW_STEP, MAX_YAW_STEP));
	}

	/**
	 * 判断脚下是否有可碰撞方块支撑（“真正着地”）：检查脚底正下方一小段水平切片
	 * （脚底往下 {@value #GROUND_SLICE_BELOW} 格厚）是否与任何方块碰撞。
	 * 不依赖 {@code Entity.onGround}——bot 的纯水平 move() 不刷新该标志，
	 * 走出平台边缘后它仍是 true，会导致重力失效而浮空。
	 */
	private static boolean hasGroundBelow(AiAssistantPlayer player) {
		if (player.level() instanceof ServerLevel level) {
			AABB box = player.getBoundingBox();
			AABB slice = new AABB(
					box.minX + 0.1, box.minY - GROUND_SLICE_BELOW, box.minZ + 0.1,
					box.maxX - 0.1, box.minY, box.maxZ - 0.1);
			return level.getBlockCollisions(player, slice).iterator().hasNext();
		}
		return player.onGround();
	}
}