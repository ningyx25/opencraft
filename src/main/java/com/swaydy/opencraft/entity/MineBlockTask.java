package com.swaydy.opencraft.entity;

import com.swaydy.opencraft.ai.AiCompanionService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 挖掘指定方块的任务。
 *
 * 助手先寻路到目标旁，然后持续挥动手臂并调用 {@code level.destroyBlock} 破坏方块；
 * 掉落物直接交给主人（进入主人背包，背包满则掉落到主人脚边）。
 *
 * 由挖掘插件先在服务端线程校验安全性（距离、可破坏性、功能方块），
 * 再下达本任务；任务自身只负责执行与判定。
 */
public class MineBlockTask extends AssistantTask {
	private static final int TIMEOUT_TICKS = 600; // 30s
	private static final int PATH_RECHECK = 10;
	private static final int SWING_TICKS = 6;

	private final ServerLevel level;
	private final BlockPos target;
	private final ServerPlayer owner;
	private final long deadlineTick;

	private boolean arrived = false;
	private boolean done = false;
	private boolean failed = false;
	private int pathRecalc = 0;
	private int swingTimer = 0;

	public MineBlockTask(AiAssistantEntity assistant, ServerLevel level, BlockPos target,
	                     ServerPlayer owner) {
		super(assistant);
		this.level = level;
		this.target = target.immutable();
		this.owner = owner;
		this.deadlineTick = assistant.tickCount + TIMEOUT_TICKS;
	}

	@Override
	public void start() {
		this.pathRecalc = 0;
		this.swingTimer = 0;
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
		BlockState state = level.getBlockState(target);
		if (state.isAir()) {
			// 目标已是空气：挖掘完成（可能是被其他机制挖掉）
			done = true;
			assistant.getNavigation().stop();
			return;
		}

		// 1) 先靠近目标（水平距离 ≤ 2.5 格且视线可达）
		double dx = assistant.getX() - (target.getX() + 0.5);
		double dz = assistant.getZ() - (target.getZ() + 0.5);
		double distSq = dx * dx + dz * dz;
		if (distSq > 2.5 * 2.5) {
			if (--pathRecalc <= 0) {
				pathRecalc = PATH_RECHECK;
				boolean moved = assistant.getNavigation().moveTo(
						target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5,
						assistant.getConfig().speed);
				if (!moved) {
					// 无法寻路：尝试向上爬一格
					assistant.getNavigation().moveTo(
							target.getX() + 0.5, target.getY() + 1.0, target.getZ() + 0.5,
							assistant.getConfig().speed);
				}
			}
			return;
		}

		// 2) 已就位：持续挥动 + 破坏
		arrived = true;
		assistant.getLookControl().setLookAt(
				target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, 30.0F, 30.0F);
		if (--swingTimer <= 0) {
			swingTimer = SWING_TICKS;
			assistant.swing(InteractionHand.MAIN_HAND);
			destroyTarget();
		}
	}

	/**
	 * 破坏目标方块并把掉落物交给主人。破坏走 {@code level.destroyBlock}（与
	 * ServerPlayerGameMode.destroyBlock 同款逻辑，由服务端执行）。
	 */
	private void destroyTarget() {
		BlockState state = level.getBlockState(target);
		if (state.isAir() || state.is(net.minecraft.world.level.block.Blocks.BEDROCK)) {
			return;
		}
		// 播放破坏音效与粒子（服务端广播）
		level.playSound(null, target, SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 0.8F, 1.0F);
		level.levelEvent(assistant, 2001, target, Block.getId(state));
		// 收集掉落物再破坏（避免破坏后 getDrops 取不到状态）
		java.util.List<ItemStack> drops = Block.getDrops(state, level, target,
				level.getBlockEntity(target), assistant, ItemStack.EMPTY);
		boolean broken = level.destroyBlock(target, false, assistant, 3);
		if (!broken) {
			// 破坏失败（如基岩）：标记失败，停止尝试
			failed = true;
			assistant.getNavigation().stop();
			return;
		}
		for (ItemStack stack : drops) {
			if (!stack.isEmpty()) {
				giveToOwner(stack);
			}
		}
		done = true;
		assistant.getNavigation().stop();
	}

	/** 掉落物进主人背包；背包满则掉落到主人脚边。 */
	private void giveToOwner(ItemStack stack) {
		if (owner == null) {
			// 主人不可用：掉落到挖掘点附近
			level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(level,
					target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, stack));
			return;
		}
		if (owner.getInventory().add(stack)) {
			AiCompanionService.notifyInventoryGain(owner, stack);
		} else {
			owner.drop(stack, false);
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
		return "正在挖掘 (" + target.getX() + "," + target.getY() + "," + target.getZ() + ")";
	}

	public BlockPos getTarget() {
		return target;
	}
}