package com.swaydy.opencraft.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 一个像 AI logo 的功能方块：
 * - powered=false：默认外观（青色纠缠环）
 * - powered=true ：贴图切成品红色 + 方块自身发光（亮度 15）
 * 玩家右键点击可在两种状态间切换，同时在聊天里给玩家一句提示。
 */
public class AiLogoBlock extends Block {
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

	/**
	 * 玩家空手右键点击方块时触发（1.21.11 API：useWithoutItem，返回 InteractionResult）。
	 */
	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
	                                           Player player, BlockHitResult hit) {
		boolean nowPowered = !state.getValue(POWERED);
		// 切换 blockstate；UPDATE_ALL(3) = 通知邻居 + 同步客户端
		level.setBlock(pos, state.setValue(POWERED, nowPowered), Block.UPDATE_ALL);
		// 播放一个反馈音
		level.playSound(null, pos,
				nowPowered ? SoundEvents.BEACON_ACTIVATE : SoundEvents.BEACON_DEACTIVATE,
				SoundSource.BLOCKS, 0.6F, nowPowered ? 1.4F : 0.8F);
		// 只在服务端给玩家提示一次
		if (!level.isClientSide()) {
			player.displayClientMessage(
					Component.translatable(nowPowered
							? "block.opencraft.ai_logo_block.activated"
							: "block.opencraft.ai_logo_block.deactivated"),
					true // action bar 上方显示
			);
		}
		return InteractionResult.SUCCESS;
	}
}
