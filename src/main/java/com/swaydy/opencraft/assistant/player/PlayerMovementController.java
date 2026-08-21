package com.swaydy.opencraft.assistant.player;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.state.BlockState;
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
 * - <b>跳跃</b>：显式指令 {@link #jump()}（模型 player_jump）与主动爬台阶
 *   （{@link #climbableStepAhead}）都会把 vy 设为跳跃初速度；2 格以上高墙不浪费
 *   跳（交给传送回退）；
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
	/** 本控制器所属的机器人（由每 tick 刷新；供显式跳跃判定着地/飞行）。 */
	private AiAssistantPlayer owner;

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
		com.swaydy.opencraft.debug.DebugLog.log("movement",
				"移动指令{}下达：目标 ({},{},{}) 速度 {}",
				manual ? "（手动）" : "", (int) target.x, (int) target.y, (int) target.z,
				speed);
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
		if (this.target != null) {
			com.swaydy.opencraft.debug.DebugLog.log("movement",
					"移动已停止（原目标 ({},{},{})）",
					(int) this.target.x, (int) this.target.y, (int) this.target.z);
		}
		this.target = null;
		this.onArrived = null;
		this.arrivedFired = false;
		this.manual = false;
	}

	/**
	 * 命令机器人跳一下（显式跳跃，模型通过 player_jump 触发）。原地直跳；
	 * 配合移动目标（player_goto/player_mine 等）会保持前进方向，形成助跑跳，
	 * 可越过 1 格台阶/小沟。着地时才有效；空中/飞行中拒绝（不连跳、不加力）。
	 *
	 * @return 是否真的起跳
	 */
	public boolean jump() {
		if (owner == null || owner.isRemoved() || owner.getAbilities().flying) {
			return false;
		}
		if (vy > 0.01 || !hasGroundBelow(owner)) {
			return false; // 已在上升/半空：不连续加力
		}
		vy = JUMP_SPEED;
		com.swaydy.opencraft.debug.DebugLog.log("movement", "显式跳跃指令（vy={}）", JUMP_SPEED);
		return true;
	}

	/** 每 tick 驱动：重力/跳跃/前进/朝向/传送回退。在服务端线程调用。 */
	public void tick(AiAssistantPlayer player) {
		if (player == null || player.isRemoved()) {
			return;
		}
		this.owner = player;
		Vec3 pos = player.position();
		boolean flying = player.getAbilities().flying;
		// 着地判定：实时查脚下方块，不依赖可能陈旧的 onGround 标志（见类注释）
		boolean grounded = hasGroundBelow(player);
		// 重力：未着地时加速下落（封顶）；着地时——若正带上升速度（刚起跳/主动爬台阶）
		// 则保留 vy 让它生效，否则清零贴地。
		// ⚠ 关键：不能无条件在着地时 vy=0，否则跳跃初速度会在下一 tick 被抹掉、永远跳不起来
		// （这正是旧版“卡住反射跳”从未真正起跳的原因）。
		if (!flying && !grounded) {
			vy -= GRAVITY;
			if (vy < MAX_FALL_SPEED) {
				vy = MAX_FALL_SPEED;
			}
		} else if (flying || vy <= 0.0) {
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
			// 到达目标：手动标记复位（manual 只表示“一次手动移动进行中”）——
			// 到达后即清除，这样跟随逻辑（以 !isManual() 门控）能在手动移动完成后
			// 自动接管；否则一次 player_goto/player_mine 之后 manual 永远为 true，
			// 跟随将永远无法恢复。
			this.manual = false;
			// 到达：先执行“到达动作”（如挖掘），否则只停在这里
			if (onArrived != null && !arrivedFired) {
				com.swaydy.opencraft.debug.DebugLog.log("movement",
						"到达目标 ({},{},{})，开始执行到达动作",
						(int) pos.x, (int) pos.y, (int) pos.z);
				arrivedFired = true;
				Runnable act = onArrived;
				onArrived = null;
				target = null;
				act.run();
			} else {
				com.swaydy.opencraft.debug.DebugLog.log("movement",
						"到达目标 ({},{},{})", (int) pos.x, (int) pos.y, (int) pos.z);
				target = null;
			}
			return;
		}
		double step = Math.min(MAX_STEP * speed, horiz);
		double mx = dx / horiz * step;
		double mz = dz / horiz * step;
		// 主动爬台阶：着地、未在上升、前方是 1 格实心台阶（其正上方空气）→ 提前起跳
		// （行进方向、距目标仍远时也会跳，不等“卡住”才反射——更像真玩家自己跃上台阶）
		if (grounded && !flying && vy <= 0.01 && climbableStepAhead(player, mx, mz)) {
			vy = JUMP_SPEED;
		}
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
			// 被挡：仅当前方是 1 格台阶时跳（主动探测漏掉时的兜底）；
			// 2 格以上高墙不浪费跳（继续卡住最终触发传送回退）
			if (grounded && !flying && vy <= 0.01
					&& climbableStepAhead(player, mx, mz)) {
				vy = JUMP_SPEED;
			}
		} else {
			stuckTicks = 0;
		}
		if (stuckTicks > STUCK_TELEPORT_TICKS) {
			// 长时间卡住（悬崖/水/墙）：传送靠近目标，避免永远卡住
			com.swaydy.opencraft.debug.DebugLog.log("movement",
					"玩家形态助手卡住 {} tick，传送到目标 ({},{},{})（原位置 ({},{},{})）",
					STUCK_TELEPORT_TICKS, (int) target.x, (int) target.y, (int) target.z,
					(int) pos.x, (int) pos.y, (int) pos.z);
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
	 * 判断移动方向前方是否是一个“1 格实心台阶”（可跳上去爬升）：取水平主方向
	 * （|mx|≥|mz| 取 X 向，否则取 Z 向）上前方脚部那一格是实心整块方块、且其正上方
	 * 是空气 → 是 1 格台阶，跳起来就能翻过去。2 格以上高墙（上方也是实心）返回 false，
	 * 避免无效傻跳（交给卡住传送回退）。
	 */
	private static boolean climbableStepAhead(AiAssistantPlayer player, double mx, double mz) {
		if (mx == 0 && mz == 0 || !(player.level() instanceof ServerLevel level)) {
			return false;
		}
		int sx;
		int sz;
		if (Math.abs(mx) >= Math.abs(mz)) {
			sx = mx > 0 ? 1 : -1;
			sz = 0;
		} else {
			sx = 0;
			sz = mz > 0 ? 1 : -1;
		}
		BlockPos pos = player.blockPosition();
		BlockPos foot = pos.offset(sx, 0, sz);
		BlockState footState = level.getBlockState(foot);
		if (footState.isAir() || !footState.isCollisionShapeFullBlock(level, foot)) {
			return false; // 前方不是实心整块（空隙/半砖/栅栏等，跳了也过不去或不需要跳）
		}
		BlockState headState = level.getBlockState(foot.above());
		return headState.isAir();
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