package com.swaydy.opencraft.net;

import com.swaydy.opencraft.OpenCraftMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * “右键 AI 助手”打开背包界面使用的自定义网络包。
 *
 * 服务端打开 {@code AssistantInventoryMenu} 后把助手【实体 ID】紧随打开包发给客户端
 * （同一连接内按序到达，屏幕已建好），背包界面左侧用原版
 * {@code renderEntityInInventory} 渲染这个实体的模型。
 */
public final class AssistantPayloads {
	private AssistantPayloads() {
	}

	/** 服务器 → 客户端：背包界面要渲染的助手实体 ID。 */
	public record AssistantInteractPayload(int entityId)
			implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<AssistantInteractPayload> TYPE =
				new CustomPacketPayload.Type<>(OpenCraftMod.id("assistant_interact"));
		public static final StreamCodec<ByteBuf, AssistantInteractPayload> STREAM_CODEC =
				StreamCodec.composite(
						ByteBufCodecs.VAR_INT, AssistantInteractPayload::entityId,
						AssistantInteractPayload::new);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * 服务器 → 客户端：玩家形态助手的皮肤选择（bot UUID → 内置皮肤 id，见 AssistantSkins）。
	 * "default" 表示不替换（客户端清除映射，回退原版皮肤解析）。
	 * 皮肤 id 与贴图解耦：服务端只同步 id 字符串，贴图随模组在客户端分发
	 * （assets/opencraft/textures/skins/<id>.png）。
	 */
	public record AssistantSkinPayload(java.util.UUID botUuid, String skinId)
			implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<AssistantSkinPayload> TYPE =
				new CustomPacketPayload.Type<>(OpenCraftMod.id("assistant_skin"));
		public static final StreamCodec<ByteBuf, AssistantSkinPayload> STREAM_CODEC =
				StreamCodec.composite(
						net.minecraft.core.UUIDUtil.STREAM_CODEC, AssistantSkinPayload::botUuid,
						ByteBufCodecs.STRING_UTF8, AssistantSkinPayload::skinId,
						AssistantSkinPayload::new);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
