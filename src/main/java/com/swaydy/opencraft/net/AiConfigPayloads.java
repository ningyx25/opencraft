package com.swaydy.opencraft.net;

import com.swaydy.opencraft.OpenCraftMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
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

	/** 客户端 → 服务器：从配置界面的聊天窗口向本方块的助手发送一条消息。 */
	public record AiConfigChatPayload(String message, BlockPos pos, ResourceKey<Level> dimension)
			implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<AiConfigChatPayload> TYPE =
				new CustomPacketPayload.Type<>(OpenCraftMod.id("ai_config_chat"));
		public static final StreamCodec<ByteBuf, AiConfigChatPayload> STREAM_CODEC =
				StreamCodec.composite(
						ByteBufCodecs.STRING_UTF8, AiConfigChatPayload::message,
						BlockPos.STREAM_CODEC, AiConfigChatPayload::pos,
						DIMENSION_CODEC, AiConfigChatPayload::dimension,
						AiConfigChatPayload::new);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** 客户端 → 服务器：请求本方块助手的对话历史（聊天窗口打开时填充）。 */
	public record AiConfigChatHistoryPayload(BlockPos pos, ResourceKey<Level> dimension)
			implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<AiConfigChatHistoryPayload> TYPE =
				new CustomPacketPayload.Type<>(OpenCraftMod.id("ai_config_chat_history"));
		public static final StreamCodec<ByteBuf, AiConfigChatHistoryPayload> STREAM_CODEC =
				StreamCodec.composite(
						BlockPos.STREAM_CODEC, AiConfigChatHistoryPayload::pos,
						DIMENSION_CODEC, AiConfigChatHistoryPayload::dimension,
						AiConfigChatHistoryPayload::new);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** 客户端 → 服务器：中断本方块助手正在进行的任务（聊天窗口「中断」按钮；卡住时可立即重新提问）。 */
	public record AiConfigInterruptPayload(BlockPos pos, ResourceKey<Level> dimension)
			implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<AiConfigInterruptPayload> TYPE =
				new CustomPacketPayload.Type<>(OpenCraftMod.id("ai_config_interrupt"));
		public static final StreamCodec<ByteBuf, AiConfigInterruptPayload> STREAM_CODEC =
				StreamCodec.composite(
						BlockPos.STREAM_CODEC, AiConfigInterruptPayload::pos,
						DIMENSION_CODEC, AiConfigInterruptPayload::dimension,
						AiConfigInterruptPayload::new);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 服务器 → 客户端：配置界面聊天窗口的事件。
	 * kind 取值：
	 * - "history"  —— 对话历史快照（text 为 JSON 数组 [{"role","content"},...]，客户端替换整个窗口）；
	 * - "thinking" —— 助手开始思考（客户端显示“正在思考…”占位）；
	 * - "delta"    —— 流式回复增量（客户端追加到当前助手气泡）；
	 * - "reply"    —— 流式结束的完整回复（客户端用其替换/收尾当前气泡）；
	 * - "error"    —— 出错（text 为可直接渲染的 Component JSON，含翻译）。
	 */
	public record AiConfigChatEventPayload(String kind, Component text,
	                                       BlockPos pos, ResourceKey<Level> dimension)
			implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<AiConfigChatEventPayload> TYPE =
				new CustomPacketPayload.Type<>(OpenCraftMod.id("ai_config_chat_event"));
		public static final StreamCodec<ByteBuf, AiConfigChatEventPayload> STREAM_CODEC =
				StreamCodec.composite(
						ByteBufCodecs.STRING_UTF8, AiConfigChatEventPayload::kind,
						ComponentSerialization.TRUSTED_CONTEXT_FREE_STREAM_CODEC,
						AiConfigChatEventPayload::text,
						BlockPos.STREAM_CODEC, AiConfigChatEventPayload::pos,
						DIMENSION_CODEC, AiConfigChatEventPayload::dimension,
						AiConfigChatEventPayload::new);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
