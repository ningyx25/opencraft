package com.swaydy.opencraft.net;

import com.swaydy.opencraft.OpenCraftMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * AI 配置编辑器使用的自定义网络包。
 *
 * 配置保存在游戏里的 AI 徽标方块实体中，因此每个包都携带目标方块的
 * 坐标 + 维度；服务端只接受指向 AI 徽标方块实体的请求。
 */
public final class AiConfigPayloads {
	private AiConfigPayloads() {
	}

	public static final StreamCodec<ByteBuf, ResourceKey<Level>> DIMENSION_CODEC =
			ResourceKey.streamCodec(Registries.DIMENSION);

	/**
	 * 服务器 → 客户端：某个 AI 徽标方块的配置数据。
	 * canEdit 表示是否有权限保存；bound/boundByMe 表示该方块当前是否已绑定助手、
	 * 以及是否绑定的是本玩家自己的助手（配置界面据此把“召唤/送走”合并为同一个按钮）。
	 */
	public record AiConfigDataPayload(String json, boolean canEdit, boolean bound, boolean boundByMe,
	                                  BlockPos pos, ResourceKey<Level> dimension)
			implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<AiConfigDataPayload> TYPE =
				new CustomPacketPayload.Type<>(OpenCraftMod.id("ai_config_data"));
		public static final StreamCodec<ByteBuf, AiConfigDataPayload> STREAM_CODEC =
				StreamCodec.composite(
						ByteBufCodecs.STRING_UTF8, AiConfigDataPayload::json,
						ByteBufCodecs.BOOL, AiConfigDataPayload::canEdit,
						ByteBufCodecs.BOOL, AiConfigDataPayload::bound,
						ByteBufCodecs.BOOL, AiConfigDataPayload::boundByMe,
						BlockPos.STREAM_CODEC, AiConfigDataPayload::pos,
						DIMENSION_CODEC, AiConfigDataPayload::dimension,
						AiConfigDataPayload::new);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** 客户端 → 服务器：把编辑后的配置保存到指定方块。 */
	public record AiConfigSavePayload(String json, BlockPos pos, ResourceKey<Level> dimension)
			implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<AiConfigSavePayload> TYPE =
				new CustomPacketPayload.Type<>(OpenCraftMod.id("ai_config_save"));
		public static final StreamCodec<ByteBuf, AiConfigSavePayload> STREAM_CODEC =
				StreamCodec.composite(
						ByteBufCodecs.STRING_UTF8, AiConfigSavePayload::json,
						BlockPos.STREAM_CODEC, AiConfigSavePayload::pos,
						DIMENSION_CODEC, AiConfigSavePayload::dimension,
						AiConfigSavePayload::new);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** 客户端 → 服务器：用指定方块召唤并绑定 AI 助手（使用该方块的配置）。 */
	public record AiConfigSummonPayload(BlockPos pos, ResourceKey<Level> dimension)
			implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<AiConfigSummonPayload> TYPE =
				new CustomPacketPayload.Type<>(OpenCraftMod.id("ai_config_summon"));
		public static final StreamCodec<ByteBuf, AiConfigSummonPayload> STREAM_CODEC =
				StreamCodec.composite(
						BlockPos.STREAM_CODEC, AiConfigSummonPayload::pos,
						DIMENSION_CODEC, AiConfigSummonPayload::dimension,
						AiConfigSummonPayload::new);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** 客户端 → 服务器：送走绑定到指定方块的 AI 助手（取消召唤，按钮的“不召唤”状态）。 */
	public record AiConfigDismissPayload(BlockPos pos, ResourceKey<Level> dimension)
			implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<AiConfigDismissPayload> TYPE =
				new CustomPacketPayload.Type<>(OpenCraftMod.id("ai_config_dismiss"));
		public static final StreamCodec<ByteBuf, AiConfigDismissPayload> STREAM_CODEC =
				StreamCodec.composite(
						BlockPos.STREAM_CODEC, AiConfigDismissPayload::pos,
						DIMENSION_CODEC, AiConfigDismissPayload::dimension,
						AiConfigDismissPayload::new);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
