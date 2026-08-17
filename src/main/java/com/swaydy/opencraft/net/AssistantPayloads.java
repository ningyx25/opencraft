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
 * “右键 AI 助手互动”使用的自定义网络包。
 *
 * 目标助手用【实体 ID】标识（右键点击时就在玩家所在的维度里，直接按 ID 取实体）；
 * 服务端每次都会重新校验“实体存在 + 是本玩家的助手”，不信任客户端。
 * 聊天回复走与配置界面聊天窗口相同的 S2C 事件通道（AiConfigChatEventPayload，
 * 按绑定方块坐标路由），因此互动界面也要携带助手的绑定方块坐标。
 */
public final class AssistantPayloads {
	private AssistantPayloads() {
	}

	public static final StreamCodec<ByteBuf, ResourceKey<Level>> DIMENSION_CODEC =
			ResourceKey.streamCodec(Registries.DIMENSION);

	/**
	 * 服务器 → 客户端：打开（或刷新）AI 助手互动界面。
	 * displayName 为助手的显示名（名字 + 坐标）；following 为当前跟随状态；
	 * isOwner 表示是否本玩家的助手（决定“送走”按钮）；model 为绑定方块配置的模型名；
	 * agent 为绑定方块配置的 Agent 预设 id（只读展示）；
	 * blockPos/dimension 为助手绑定方块的坐标（聊天回复的 S2C 事件按它路由回本界面）。
	 */
	public record AssistantInteractPayload(int entityId, String displayName, boolean following,
	                                       boolean isOwner, String model, String agent,
	                                       BlockPos blockPos, ResourceKey<Level> dimension)
			implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<AssistantInteractPayload> TYPE =
				new CustomPacketPayload.Type<>(OpenCraftMod.id("assistant_interact"));
		public static final StreamCodec<ByteBuf, AssistantInteractPayload> STREAM_CODEC =
				StreamCodec.composite(
						ByteBufCodecs.VAR_INT, AssistantInteractPayload::entityId,
						ByteBufCodecs.STRING_UTF8, AssistantInteractPayload::displayName,
						ByteBufCodecs.BOOL, AssistantInteractPayload::following,
						ByteBufCodecs.BOOL, AssistantInteractPayload::isOwner,
						ByteBufCodecs.STRING_UTF8, AssistantInteractPayload::model,
						ByteBufCodecs.STRING_UTF8, AssistantInteractPayload::agent,
						BlockPos.STREAM_CODEC, AssistantInteractPayload::blockPos,
						DIMENSION_CODEC, AssistantInteractPayload::dimension,
						AssistantInteractPayload::new);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** 客户端 → 服务器：向指定助手发一条消息（它会用自己绑定的配置回复）。 */
	public record AssistantChatPayload(int entityId, String message)
			implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<AssistantChatPayload> TYPE =
				new CustomPacketPayload.Type<>(OpenCraftMod.id("assistant_chat"));
		public static final StreamCodec<ByteBuf, AssistantChatPayload> STREAM_CODEC =
				StreamCodec.composite(
						ByteBufCodecs.VAR_INT, AssistantChatPayload::entityId,
						ByteBufCodecs.STRING_UTF8, AssistantChatPayload::message,
						AssistantChatPayload::new);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** 客户端 → 服务器：切换指定助手的跟随/待命模式。 */
	public record AssistantToggleFollowPayload(int entityId)
			implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<AssistantToggleFollowPayload> TYPE =
				new CustomPacketPayload.Type<>(OpenCraftMod.id("assistant_toggle_follow"));
		public static final StreamCodec<ByteBuf, AssistantToggleFollowPayload> STREAM_CODEC =
				StreamCodec.composite(
						ByteBufCodecs.VAR_INT, AssistantToggleFollowPayload::entityId,
						AssistantToggleFollowPayload::new);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** 客户端 → 服务器：送走指定助手（只有主人可以）。 */
	public record AssistantDismissPayload(int entityId)
			implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<AssistantDismissPayload> TYPE =
				new CustomPacketPayload.Type<>(OpenCraftMod.id("assistant_dismiss"));
		public static final StreamCodec<ByteBuf, AssistantDismissPayload> STREAM_CODEC =
				StreamCodec.composite(
						ByteBufCodecs.VAR_INT, AssistantDismissPayload::entityId,
						AssistantDismissPayload::new);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
