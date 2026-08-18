package com.swaydy.opencraft.assistant.player;

import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * “黑洞”网络连接：玩家形态助手（bot）没有真实客户端，发出的包全部丢弃。
 *
 * 原理（与 Carpet 的 FakeClientConnection 一致）：{@code new Connection(PacketFlow.SERVERBOUND)}
 * 构造后 channel 为 null——{@code send()/disconnect()} 天然无害（send 进 pendingActions 队列、
 * disconnect 只记 delayedDisconnect）。唯一会炸的是 {@code setupInboundProtocol()}——
 * 它需要 channel 来配置 pipeline，必须在子类里重写为 no-op。
 */
public class FakeConnection extends Connection {
	public FakeConnection() {
		super(PacketFlow.SERVERBOUND);
	}

	@Override
	public boolean isMemoryConnection() {
		return true;
	}

	@Override
	public boolean isConnected() {
		return false;
	}

	@Override
	public SocketAddress getRemoteAddress() {
		return new InetSocketAddress("bot", 0);
	}

	@Override
	public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocolInfo, T packetListener) {
		// 无 channel：跳过 pipeline 配置（PlayerList.placeNewPlayer 会调用）
	}

	@Override
	public void setupOutboundProtocol(ProtocolInfo<?> protocolInfo) {
		// 无 channel：跳过
	}

	@Override
	public void disconnect(Component reason) {
		// 黑洞：不真正断开
	}

	@Override
	public void disconnect(DisconnectionDetails details) {
		// 黑洞：不真正断开
	}
}
