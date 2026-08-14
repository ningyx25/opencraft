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

	/** 服务器 → 客户端：某个 AI 徽标方块的配置数据（canEdit 表示是否有权限保存）。 */
	public record AiConfigDataPayload(String json, boolean canEdit,
	                                  BlockPos pos, ResourceKey<Level> dimension)
			implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<AiConfigDataPayload> TYPE =
				new CustomPacketPayload.Type<>(OpenCraftMod.id("ai_config_data"));
		public static final StreamCodec<ByteBuf, AiConfigDataPayload> STREAM_CODEC =
				StreamCodec.composite(
						ByteBufCodecs.STRING_UTF8, AiConfigDataPayload::json,
						ByteBufCodecs.BOOL, AiConfigDataPayload::canEdit,
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
}
