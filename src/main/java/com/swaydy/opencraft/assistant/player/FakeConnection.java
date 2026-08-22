package com.swaydy.opencraft.assistant.player;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.function.Consumer;

/**
 * “黑洞”网络连接：玩家形态助手（bot）没有真实客户端，发往它的包全部丢弃。
 *
 * <p>覆写要点（与 Carpet 的 FakeClientConnection 同思路）：
 * <ul>
 * <li>{@code setupInboundProtocol/setupOutboundProtocol} 需要真实 channel 配置 pipeline，
 *     必须重写为 no-op（{@code PlayerList.placeNewPlayer} 会调用）；</li>
 * <li>{@code send/runOnceConnected/flushChannel} **必须显式丢弃**——父类实现在
 *     {@code isConnected()==false}（本类恒为 false）时会把每个包
 *     {@code pendingActions.add(...)} 永久积压：bot 没有客户端、channel 永远不会出现，
 *     队列永远不会被冲刷。bot 移动时区块/实体追踪等包源源不断发来，不丢弃就是
 *     无界内存泄漏。</li>
 * </ul>
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
	public void send(Packet<?> packet, @Nullable ChannelFutureListener channelFutureListener, boolean flush) {
		// 黑洞：丢弃（send(Packet)/send(Packet, listener) 都汇入本重载）
	}

	@Override
	public void runOnceConnected(Consumer<Connection> consumer) {
		// 黑洞：永不连接，不排队
	}

	@Override
	public void flushChannel() {
		// 黑洞：无通道可冲刷
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
