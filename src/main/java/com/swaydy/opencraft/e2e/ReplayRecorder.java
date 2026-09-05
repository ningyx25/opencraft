package com.swaydy.opencraft.e2e;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.swaydy.opencraft.OpenCraftMod;
import com.swaydy.opencraft.assistant.player.AiAssistantPlayer;
import com.swaydy.opencraft.assistant.player.FakeConnection;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.SharedConstants;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ServerboundSelectKnownPacks;
import net.minecraft.network.protocol.handshake.ClientIntent;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundLoginAcknowledgedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.ServerHandshakePacketListenerImpl;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.world.level.GameType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 服务端 Replay 录制器：让一个「录制假玩家」走完真实客户端的完整进服流程
 * （login → configuration → play），连接把服务器在所有阶段发出的客户端包按
 * ReplayMod {@code .mcpr} 格式落盘。ReplayMod 回放时从 login 阶段解码，
 * 因此必须记录完整序列（含 configuration 阶段的注册表同步），不能只录 play。
 *
 * <p>实现要点：</p>
 * <ul>
 * <li>{@link RecordingConnection} 继承 {@link FakeConnection}（无 channel）：
 *     {@code setupOutboundProtocol} 记录当前阶段协议（login/config/play），
 *     {@code setupInboundProtocol} 记录当前监听器，{@code send} 用该阶段
 *     协议的 {@link StreamCodec} 编码线上字节，帧格式
 *     {@code int32 时间戳 + int32 长度 + 包字节}（大端）；</li>
 * <li>握手/登录/配置阶段由本类直接驱动（等价于真实客户端回 serverbound 包）：
 *     intention → hello → login tick/ack → Fabric 配置握手 pong →
 *     client information → known packs 应答 → 配置完成应答（跨 tick 重试，
 *     等出生区块异步加载），随后原版 placeNewPlayer 发出 play 登录序列；</li>
 * <li>进入 play 后玩家切旁观、每 tick 传送到助手身边并推进区块跟踪/模拟
 *     chunk-batch ack（假玩家没有客户端回包，不模拟会导致首批区块后停发）。</li>
 * </ul>
 */
public final class ReplayRecorder {
	private static final String CAMERA_NAME = "E2E_replay";
	/** Fabric Networking API 配置握手的约定 ping id（其 addon 等客户端回这个 pong 后才放行配置）。 */
	private static final int FABRIC_CONFIG_PING = 0xFAB71C;

	private final MinecraftServer server;
	private final String taskId;
	private final String stamp;
	private final long startMs = System.currentTimeMillis();
	private final Path tmpFile;
	private final DataOutputStream out;
	private final RecordingConnection connection;
	private final Function<ByteBuf, RegistryFriendlyByteBuf> bufDecorator;

	private volatile AiAssistantPlayer target;
	private volatile boolean stopped;
	/** play 阶段就位的录制玩家（login/config 阶段为 null）。 */
	private volatile ServerPlayer camera;
	private boolean configHandshakeDone;
	private boolean spectatorSet;
	/** 录制期间出现过的玩家名（metaData players）。 */
	private final Set<String> seenPlayers = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private ReplayRecorder(MinecraftServer server, String taskId, String stamp) throws Exception {
		this.server = server;
		this.taskId = taskId;
		this.stamp = stamp;
		this.tmpFile = Path.of("replays", ".e2e-" + taskId + "-" + stamp + ".tmcpr");
		Files.createDirectories(tmpFile.getParent());
		OutputStream raw = new BufferedOutputStream(Files.newOutputStream(tmpFile));
		this.out = new DataOutputStream(raw);
		this.bufDecorator = RegistryFriendlyByteBuf.decorator(server.registryAccess());
		this.connection = new RecordingConnection();

		// 握手：intention=LOGIN（服务端据此建 ServerLoginPacketListenerImpl 并切到 login 协议）
		ServerHandshakePacketListenerImpl handshake = new ServerHandshakePacketListenerImpl(server, connection);
		handshake.handleIntention(new ClientIntentionPacket(
				SharedConstants.getProtocolVersion(), "127.0.0.1", 25565, ClientIntent.LOGIN));

		// 登录：hello（内存连接走离线档案）→ tick 推进 VERIFYING → 服务端发 LoginFinished
		ServerLoginPacketListenerImpl login = (ServerLoginPacketListenerImpl) connection.getPacketListener();
		login.handleHello(new ServerboundHelloPacket(CAMERA_NAME, null));
		login.tick();
		// 登录确认 → 服务端建配置监听器并 startConfiguration
		//（Fabric 网络 API 会打断首次配置流程，等我们回约定 pong 才放行，见 driveConfiguration）
		login.handleLoginAcknowledgement(ServerboundLoginAcknowledgedPacket.INSTANCE);

		seenPlayers.add(CAMERA_NAME);
		ServerTickEvents.END_SERVER_TICK.register(s -> tick());
	}

	/** 创建并启动录制；失败返回 null（不影响 e2e 主流程）。 */
	public static ReplayRecorder start(MinecraftServer server, String taskId, String stamp) {
		try {
			ReplayRecorder recorder = new ReplayRecorder(server, taskId, stamp);
			OpenCraftMod.LOGGER.info("[OpenCraft] [E2E] Replay 录制已开始（完整连接录制，录制玩家 {})", CAMERA_NAME);
			return recorder;
		} catch (Exception e) {
			OpenCraftMod.LOGGER.warn("[OpenCraft] [E2E] Replay 录制启动失败（不影响任务）: {}", e.toString());
			return null;
		}
	}

	/** 设置/清除跟随目标（任务助手）；由 E2EHarness 在助手召唤/解散时调用。 */
	public void setTarget(AiAssistantPlayer assistant) {
		this.target = assistant;
		if (assistant != null) {
			seenPlayers.add(assistant.getGameProfile().name());
		}
	}

	private void tick() {
		if (stopped) {
			return;
		}
		try {
			PacketListener listener = connection.getPacketListener();
			if (listener instanceof ServerConfigurationPacketListenerImpl config) {
				driveConfiguration(config);
				return;
			}
			if (!(listener instanceof ServerGamePacketListenerImpl)) {
				return; // login 阶段已在构造器内同步推进
			}
			ServerPlayer player = camera;
			if (player == null || player.isRemoved()) {
				player = server.getPlayerList().getPlayerByName(CAMERA_NAME);
				if (player == null) {
					return;
				}
				camera = player;
			}
			if (!spectatorSet) {
				spectatorSet = true;
				player.setGameMode(GameType.SPECTATOR);
			}
			AiAssistantPlayer t = target;
			if (t != null && !t.isRemoved()) {
				// 旁观者可穿墙：每 tick 贴到助手上方 2 格，区块/实体追踪范围始终覆盖行动区域。
				player.teleportTo(t.level(),
						t.getX(), t.getY() + 2.0, t.getZ(),
						Set.of(), player.getYRot(), player.getXRot(), false);
			}
			// 假玩家没有客户端移动包：手动推进区块跟踪视图 + 模拟 chunk batch 确认
			//（PlayerChunkSender 首批后等 ServerboundChunkBatchReceived 才继续发）。
			((ServerLevel) player.level()).getChunkSource().move(player);
			player.connection.chunkSender.onChunkBatchReceivedByClient(64.0f);
		} catch (Exception e) {
			OpenCraftMod.LOGGER.warn("[OpenCraft] [E2E] Replay 录制 tick 异常: {}", e.toString());
		}
	}

	/**
	 * 配置阶段驱动（每 tick）：
	 * <ol>
	 *   <li>Fabric Networking API 打断首次 startConfiguration（发注册包+ping 等客户端响应），
	 *       回约定 id 的 pong 让它重入并完成原版配置流程（注册表同步任务等才会创建）；</li>
	 *   <li>发送客户端信息并以空 known packs 应答（让服务端发完整注册表同步）；</li>
	 *   <li>配置完成回包在出生区块异步就绪前会被服务端拒绝（当前任务未到 JoinWorld），跨 tick 重试。</li>
	 * </ol>
	 */
	private void driveConfiguration(ServerConfigurationPacketListenerImpl config) {
		if (!configHandshakeDone) {
			configHandshakeDone = true;
			config.handlePong(new ServerboundPongPacket(FABRIC_CONFIG_PING));
			config.handleClientInformation(new ServerboundClientInformationPacket(ClientInformation.createDefault()));
			config.handleSelectKnownPacks(new ServerboundSelectKnownPacks(List.of()));
		}
		// 假连接的 Connection.tick() 永远不会被服务器调用，配置任务（prepare_spawn 等）
		// 只能靠这里手动推进：tick() 会让 prepare_spawn 完成并 startNextTask 到 join_world。
		config.tick();
		try {
			// 原版里由客户端收到 ClientboundFinishConfiguration 后回包触发，进而 placeNewPlayer。
			config.handleConfigurationFinished(ServerboundFinishConfigurationPacket.INSTANCE);
		} catch (IllegalStateException notReadyYet) {
			// 当前任务还不是 JoinWorldTask（出生区块在异步加载），下 tick 再试。
		}
	}

	/** 结束录制并打包 .mcpr（必须在服务端线程调用），返回相对游戏目录的 mcpr 路径。 */
	public String finish() {
		if (stopped) {
			return null;
		}
		stopped = true;
		long durationMs = System.currentTimeMillis() - startMs;
		ServerPlayer player = camera;
		if (player != null) {
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				seenPlayers.add(p.getGameProfile().name());
			}
			try {
				server.getPlayerList().remove(player);
			} catch (Exception e) {
				OpenCraftMod.LOGGER.debug("[OpenCraft] [E2E] 移除录制玩家异常: {}", e.toString());
			}
		}
		Path mcpr = Path.of("replays", "e2e-" + taskId + "-" + stamp + ".mcpr");
		try {
			synchronized (out) {
				out.flush();
			}
			out.close();
			JsonObject meta = new JsonObject();
			meta.addProperty("singleplayer", false);
			meta.addProperty("serverName", "OpenCraft e2e: " + taskId);
			meta.addProperty("duration", durationMs);
			meta.addProperty("date", startMs);
			meta.addProperty("mcversion", SharedConstants.getCurrentVersion().name());
			meta.addProperty("protocol", SharedConstants.getCurrentVersion().protocolVersion());
			meta.addProperty("fileFormat", "MCPR");
			meta.addProperty("fileFormatVersion", 14);
			meta.addProperty("generator", "opencraft-e2e");
			meta.addProperty("selfId", player != null ? player.getId() : -1);
			JsonArray players = new JsonArray();
			for (String name : seenPlayers) {
				players.add(name);
			}
			meta.add("players", players);

			try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(mcpr), StandardCharsets.UTF_8)) {
				zos.putNextEntry(new ZipEntry("recording.tmcpr"));
				Files.copy(tmpFile, zos);
				zos.closeEntry();
				zos.putNextEntry(new ZipEntry("metaData.json"));
				zos.write(meta.toString().getBytes(StandardCharsets.UTF_8));
				zos.closeEntry();
			}
			Files.deleteIfExists(tmpFile);
			OpenCraftMod.LOGGER.info("[OpenCraft] [E2E] Replay 录制完成: {}（时长 {}s）",
					mcpr.toAbsolutePath(), durationMs / 1000);
			return mcpr.toString();
		} catch (Exception e) {
			OpenCraftMod.LOGGER.warn("[OpenCraft] [E2E] Replay 打包失败: {}", e.toString());
			try {
				Files.deleteIfExists(tmpFile);
			} catch (Exception ignored) {
			}
			return null;
		}
	}

	/** 录制一个出站包（BundlePacket 拆子包；编码失败跳过，不影响 e2e）。 */
	private void recordPacket(Packet<?> packet) {
		if (stopped) {
			return;
		}
		// 不录断开包：本录制连接忽略服务端 disconnect（FakeConnection.disconnect 是 no-op），
		// 服务端可能因 Fabric 注册握手发出「requires Fabric」断开包；回放时客户端收到会中止进服。
		if (packet instanceof ClientboundDisconnectPacket) {
			return;
		}
		try {
			if (packet instanceof BundlePacket<?> bundle) {
				for (Packet<?> sub : bundle.subPackets()) {
					writeFrame(sub);
				}
			} else {
				writeFrame(packet);
			}
		} catch (Exception e) {
			OpenCraftMod.LOGGER.debug("[OpenCraft] [E2E] Replay 跳过不可编码包 {}: {}",
					packet.getClass().getSimpleName(), e.toString());
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private void writeFrame(Packet<?> packet) throws Exception {
		ProtocolInfo<?> protocol = connection.outboundProtocol;
		if (protocol == null) {
			return;
		}
		RegistryFriendlyByteBuf buf = bufDecorator.apply(Unpooled.buffer());
		byte[] bytes;
		try {
			((StreamCodec) protocol.codec()).encode(buf, packet);
			bytes = new byte[buf.readableBytes()];
			buf.readBytes(bytes);
		} finally {
			buf.release();
		}
		int timestamp = (int) Math.min(System.currentTimeMillis() - startMs, Integer.MAX_VALUE);
		synchronized (out) {
			out.writeInt(timestamp);
			out.writeInt(bytes.length);
			out.write(bytes);
		}
	}

	/**
	 * 录制连接：黑洞连接 + 记录各阶段协议/监听器 + 出站包落盘。
	 */
	private final class RecordingConnection extends FakeConnection {
		private volatile ProtocolInfo<?> outboundProtocol;
		private volatile PacketListener inboundListener;

		@Override
		public void setupOutboundProtocol(ProtocolInfo<?> protocolInfo) {
			// 记录当前阶段协议（login/config/play）；无 channel，不做 pipeline 配置。
			this.outboundProtocol = protocolInfo;
		}

		@Override
		public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocolInfo, T packetListener) {
			// 无 channel：只记住当前阶段监听器（驱动进服流程/阶段判定都靠它）。
			this.inboundListener = packetListener;
		}

		@Override
		public PacketListener getPacketListener() {
			return inboundListener;
		}

		@Override
		public boolean isConnected() {
			// 配置/游戏阶段的任务推进（isAcceptingMessages）要求连接已连接；
			// 无真实 channel，send 已覆写为落盘，无需心跳/网络栈。
			return true;
		}

		@Override
		public void send(Packet<?> packet, @org.jetbrains.annotations.Nullable ChannelFutureListener listener, boolean flush) {
			recordPacket(packet);
		}
	}
}
