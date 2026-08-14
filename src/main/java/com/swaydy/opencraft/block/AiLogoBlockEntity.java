package com.swaydy.opencraft.block;

import com.swaydy.opencraft.ai.AiBlockConfig;
import com.swaydy.opencraft.ai.AiConfigData;
import com.swaydy.opencraft.entity.AiAssistantEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * AI 徽标方块的方块实体：在游戏世界里保存 AI 助手的配置
 * （每个方块一份，随方块存档持久化，不依赖任何外部配置文件）。
 *
 * 共存性：助手与它绑定的方块共存——本方块被破坏/移除时，
 * 所有绑定它的 AI 助手也会一并消失。
 */
public class AiLogoBlockEntity extends BlockEntity {
	public static final String ID = "ai_logo_block";

	/** 本方块保存的 AI 助手配置。 */
	private final AiBlockConfig config = new AiBlockConfig();

	public AiLogoBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.AI_LOGO_BLOCK, pos, state);
	}

	public AiLogoBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	public AiBlockConfig getConfig() {
		return config;
	}

	/** 配置被修改后调用，标记方块已变更并保存。 */
	public void markConfigChanged() {
		this.setChanged();
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		config.saveAdditional(output);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		config.loadAdditional(input);
	}

	/** 供调试/测试：直接套用编辑器数据。 */
	public void applyData(AiConfigData data) {
		config.applyData(data);
		markConfigChanged();
	}

	/**
	 * 方块被破坏/替换（含活塞推动离开本格）时调用：所有绑定此方块的 AI 助手随之消失。
	 *
	 * 注意：不能重写 setRemoved() 并在其中调用 level.getBlockState()——
	 * setRemoved 在区块卸载/世界保存阶段也会触发，getBlockState 会等待主线程加载
	 * 区块，导致与 Server thread 互相等待的死锁。preRemoveSideEffects 只在方块
	 * 状态被真正替换（shouldChangedStateKeepBlockEntity 为 false）时触发，
	 * 区块卸载不会调用，天然无需守卫。
	 */
	@Override
	public void preRemoveSideEffects(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
		super.preRemoveSideEffects(pos, state);
		if (this.level == null || this.level.isClientSide()
				|| !(this.level instanceof ServerLevel serverLevel)) {
			return;
		}
		GlobalPos boundPos = GlobalPos.of(this.level.dimension(), this.worldPosition);
		java.util.List<AiAssistantEntity> bound =
				com.swaydy.opencraft.entity.ModEntities.findAssistantsBoundTo(serverLevel, boundPos);
		if (!bound.isEmpty()) {
			com.swaydy.opencraft.OpenCraftMod.LOGGER.info(
					"[OpenCraft] AI 徽标方块({})被移除，{} 个绑定的助手随之消失",
					this.worldPosition.toShortString(), bound.size());
			for (AiAssistantEntity assistant : bound) {
				assistant.discard();
			}
			// 方块没了，绑定它的助手的对话记忆一并清除
			com.swaydy.opencraft.ai.AiCompanionService.resetHistory(boundPos);
		}
	}
}
