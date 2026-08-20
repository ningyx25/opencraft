package com.swaydy.opencraft.net;

import com.swaydy.opencraft.OpenCraftMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 服务器 → 客户端：AI 助手的流式回复上屏（世界内共享流式浮层）。
 *
 * 与配置界面/互动界面的窗口事件（{@link AiConfigPayloads.AiConfigChatEventPayload}）
 * 互补：该包给【世界内浮层】推送“截至当前的已生成文本快照”（而不是窗口事件），
 * 让玩家无论从哪个入口发起对话（/opencraft ask、配置页聊天窗口、右键互动界面、
 * 或纯聊天 Agent），都能在世界里看到回复逐字出现；done=true 时浮层去掉光标并淡出。
 *
 * <p><b>sessionId</b>：同一次提问的流式会话号（服务端按玩家递增分配，每次发起一条
 * 新的流即 +1）。客户端只保留见过的最大 sessionId——旧会话（被新提问、或另一个并发
 * 助手）的迟到包一律忽略，避免多条并发流在同一个浮层上互相覆盖造成“串行/花屏”。
 */
public final class AssistantStreamPayloads {
	private AssistantStreamPayloads() {
	}

	public record AssistantStreamPayload(int sessionId, String name, String text, boolean done)
			implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<AssistantStreamPayload> TYPE =
				new CustomPacketPayload.Type<>(OpenCraftMod.id("assistant_stream"));
		public static final StreamCodec<ByteBuf, AssistantStreamPayload> STREAM_CODEC =
				StreamCodec.composite(
						ByteBufCodecs.VAR_INT, AssistantStreamPayload::sessionId,
						ByteBufCodecs.STRING_UTF8, AssistantStreamPayload::name,
						ByteBufCodecs.STRING_UTF8, AssistantStreamPayload::text,
						ByteBufCodecs.BOOL, AssistantStreamPayload::done,
						AssistantStreamPayload::new);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
