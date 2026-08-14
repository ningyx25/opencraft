package com.swaydy.opencraft.block;

import com.mojang.serialization.MapCodec;
import com.swaydy.opencraft.ai.AiConfigHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * AI 徽标方块 —— AI 助手配置的载体（游戏内唯一的配置来源，不依赖外部文件）：
 * - 普通右键：打开该方块的配置编辑器（AI 接口 / 助手行为），配置保存在本方块实体里；
 * - 潜行右键：查看状态说明。
 *
 * 共存规则：**助手与它绑定的方块共存**——方块被破坏/移除时，绑定它的助手一起消失
 * （由 AiLogoBlockEntity.preRemoveSideEffects 处理）；反之，助手被送走时方块保留，只熄灭。
 */
public class AiLogoBlock extends Block implements EntityBlock {
	public static final MapCodec<AiLogoBlock> CODEC = simpleCodec(AiLogoBlock::new);
	public static final BooleanProperty POWERED = BooleanProperty.create("powered");

	public AiLogoBlock(Properties properties) {
		super(properties);
		// 默认状态：未激活
		this.registerDefaultState(this.stateDefinition.any().setValue(POWERED, Boolean.FALSE));
	}

	@Override
	protected MapCodec<? extends AiLogoBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(POWERED);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new AiLogoBlockEntity(pos, state);
	}

	/**
	 * 玩家空手右键点击方块时触发（1.21.11 API：useWithoutItem，返回 InteractionResult）。
	 */
	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
	                                           Player player, BlockHitResult hit) {
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		if (player.isShiftKeyDown()) {
			// 潜行右键：说明状态为自动控制（由助手绑定驱动），不再手动切换
			player.displayClientMessage(
					Component.translatable(state.getValue(POWERED)
							? "block.opencraft.ai_logo_block.auto_state_on"
							: "block.opencraft.ai_logo_block.auto_state_off"),
					true);
		} else if (player instanceof ServerPlayer serverPlayer
				&& level.getBlockEntity(pos) instanceof AiLogoBlockEntity blockEntity) {
			// 普通右键：打开“本方块”的配置编辑器（配置保存在方块实体里）
			AiConfigHandler.openFor(serverPlayer, pos, level.dimension());
		}
		return InteractionResult.SUCCESS;
	}
}
