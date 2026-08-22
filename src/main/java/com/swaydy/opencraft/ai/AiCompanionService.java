package com.swaydy.opencraft.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.swaydy.opencraft.OpenCraftMod;
import com.swaydy.opencraft.agent.AgentRegistry;
import com.swaydy.opencraft.agent.AgentRuntime;
import com.swaydy.opencraft.assistant.AiAssistant;
import com.swaydy.opencraft.assistant.AssistantFacade;
import com.swaydy.opencraft.block.ModBlocks;
import com.swaydy.opencraft.net.AiConfigPayloads;
import com.swaydy.opencraft.net.AssistantStreamPayloads;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 助手服务：负责
 * - 召唤/移除玩家绑定的助手实体；
 * - 把玩家的问题交给 {@link AgentRuntime}（agentic loop，原生 function calling 工具循环）；
 * - 为每个助手维护独立的对话历史（含上下文裁剪）。
 *
 * 配置来源：AI 助手的配置完全来自它绑定的 AI 徽标方块（方块实体），
 * 不依赖任何外部配置文件；没有绑定方块时使用代码内默认值。
 *
 * 注意：所有 HTTP 请求都在独立线程池中进行，绝不阻塞服务端主线程；
 * 回调统一通过 MinecraftServer.execute 回到服务端线程。
 */
public final class AiCompanionService {
	/** 请求线程池（守护线程，服务器停止时随进程退出）。 */
	private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "opencraft-ai-worker");
		t.setDaemon(true);
		return t;
	});

	/** 序列化对话历史 JSON（配置界面聊天窗口用）。 */
	private static final Gson GSON = new Gson();

	/**
	 * 对话历史，按“助手绑定的 AI 徽标方块”键控（每个方块至多一个助手，
	 * 因此一个方块 = 一个助手 = 一份独立记忆，送走再召唤同一方块时记忆仍在）。
	 * 历史只存 user/assistant 最终文本（tool 往返不写入长期历史，避免污染与膨胀）。
	 */
	private static final Map<GlobalPos, List<LlmClient.Message>> HISTORY = new ConcurrentHashMap<>();

	private AiCompanionService() {
	}

	/** 在模组初始化时注册服务器生命周期回调。 */
	public static void init() {
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			HISTORY.clear();
			STREAM_SESSIONS.clear();
			EXECUTOR.shutdown();
			AgentRuntime.shutdown();
		});
	}

	// ------------------------------------------------------------------
	// 对外接口
	// ------------------------------------------------------------------

	/**
	 * 让玩家问助手一个问题（异步）。目标是玩家“最近”的助手（按绑定方块距离）；
	 * 还没有任何助手时自动召唤一个（绑定最近的未绑定方块）。每个助手有独立记忆。
	 */
	public static void ask(ServerPlayer player, String question) {
		AiAssistant target = AssistantFacade.findNearestFor(player);
		if (target == null) {
			// 便利：还没有助手时自动召唤一个（按方块配置的形态路由）
			target = AssistantFacade.summonNearest(player);
			if (target == null) {
				player.sendSystemMessage(Component.translatable("command.opencraft.ask.no_assistant"));
				return;
			}
		}
		ask(player, target, question);
	}

	/**
	 * 让玩家向【指定】助手提问（异步）——多助手同时存在时，用
	 * /opencraft ask <名字> <消息> 精确指定和哪个助手对话；每个助手有独立记忆，
	 * 回复会以该助手自己的名字广播到聊天。
	 */
	public static void ask(ServerPlayer player, AiAssistant assistant, String question) {
		GlobalPos historyKey = historyKeyFor(assistant);
		com.swaydy.opencraft.logging.DebugLog.log("chat",
				"玩家 {} 问助手 {}（方块 {}）: {}", player.getName().getString(),
				assistant.getConfig().effectiveName(),
				historyKey == null ? "?" : historyKey.pos().toShortString(), question);
		AgentRuntime.runAsync(player, assistant, question, historyKey, null, null);
	}

	/**
	 * 配置界面聊天窗口：向【指定】助手提问（异步），回复通过 S2C
	 * 事件回传窗口，不广播到世界聊天（私人会话）。与 {@link #ask(ServerPlayer,
	 * AiAssistant, String)} 共享同一份对话历史与 agentic loop。
	 *
	 * @param guiBlockPos / guiDimension 非空时表示 GUI 模式：流式增量以 "delta" 事件
	 *                     推送到客户端窗口，结束时以 "reply" 事件回传完整回复。
	 */
	public static void askGui(ServerPlayer player, AiAssistant assistant, String question,
	                          BlockPos guiBlockPos, ResourceKey<Level> guiDimension) {
		GlobalPos historyKey = historyKeyFor(assistant);
		com.swaydy.opencraft.logging.DebugLog.log("chat",
				"玩家 {} 通过界面问助手 {}（方块 {}）: {}", player.getName().getString(),
				assistant.getConfig().effectiveName(),
				historyKey == null ? "?" : historyKey.pos().toShortString(), question);
		AgentRuntime.runAsync(player, assistant, question, historyKey, guiBlockPos, guiDimension);
	}

	/**
	 * 召唤（或找到）绑定到指定 AI 徽标方块的助手：**一律玩家形态**（真正的 ServerPlayer bot，
	 * 像客户端一样进服），与方块配置的 Agent 预设无关（预设只决定 LLM 行为）；
	 * 自动绑定最近未绑定方块的入口见 {@link AssistantFacade#summonNearest}。
	 *
	 * 多助手规则（每个 AI 徽标方块至多绑定一个助手，跨形态统一判定）：
	 * - 目标方块已有玩家形态助手绑定：是该玩家的 → 直接返回（幂等）；是别人的 → 返回 null（拒绝）；
	 * - 目标方块未被绑定 → 新建一个绑定该方块的玩家形态助手，归召唤者所有。
	 */
	public static com.swaydy.opencraft.assistant.player.AiAssistantPlayer summonFor(ServerPlayer player, GlobalPos explicitConfigBlock) {
		return AssistantFacade.summon(player, explicitConfigBlock);
	}

	/** 对话历史的键：助手绑定的方块（每个方块一个助手、一份记忆）。 */
	private static GlobalPos historyKeyFor(AiAssistant assistant) {
		GlobalPos block = assistant.getConfigBlock();
		if (block != null) {
			return block;
		}
		return GlobalPos.of(assistant.level().dimension(), assistant.blockPosition());
	}

	/** 清空某个方块（即该方块绑定的助手）的对话历史。 */
	public static void resetHistory(GlobalPos block) {
		if (block != null) {
			HISTORY.remove(block);
			com.swaydy.opencraft.logging.DebugLog.log("history", "清空了方块 {} 的对话历史",
					block.pos().toShortString());
		}
	}

	/** 清空玩家全部助手的对话历史。 */
	public static void resetAllHistory(ServerPlayer player) {
		for (AiAssistant assistant : AssistantFacade.findAssistantsFor(player)) {
			GlobalPos block = assistant.getConfigBlock();
			if (block != null) {
				HISTORY.remove(block);
			}
		}
	}

	public static int historySize(GlobalPos block) {
		List<LlmClient.Message> list = block == null ? null : HISTORY.get(block);
		return list == null ? 0 : list.size();
	}

	// ------------------------------------------------------------------
	// 供 AgentRuntime / 任务系统调用的公开辅助（历史、显示、广播）
	// ------------------------------------------------------------------

	/** 获取某助手（按方块键控）的对话历史（可修改副本，AgentRuntime 会追加 user 消息）。 */
	public static List<LlmClient.Message> getHistory(GlobalPos key) {
		return HISTORY.computeIfAbsent(key, k -> new ArrayList<>());
	}

	/** 向某助手的历史追加一条消息（最终文本，只存 user/assistant）。 */
	public static void appendHistory(GlobalPos key, LlmClient.Message message) {
		if (key == null || message == null) {
			return;
		}
		HISTORY.computeIfAbsent(key, k -> new ArrayList<>()).add(message);
	}

	// ------------------------------------------------------------------
	// 世界内流式浮层（AssistantStreamPayloads）：所有入口的流式回复都上到这一个
	// 浮层（多行、可换行、可见完整内容），按 sessionId 路由避免并发串扰
	// ------------------------------------------------------------------

	/** 每个玩家的流式会话号（递增分配；新的流 = 新会话，覆盖旧浮层内容）。 */
	private static final Map<UUID, Integer> STREAM_SESSIONS = new ConcurrentHashMap<>();

	/** 为该玩家的新一次流式会话分配一个递增的 sessionId（并发安全）。 */
	public static int nextStreamSession(ServerPlayer player) {
		return STREAM_SESSIONS.merge(player.getUUID(), 1, Integer::sum);
	}

	/** 把流式快照推到玩家的世界内浮层（旧会话的迟到包会被客户端按 sessionId 忽略）。 */
	public static void streamOverlay(ServerPlayer player, int sessionId, String name, String snapshot) {
		sendStreamPacket(player, new AssistantStreamPayloads.AssistantStreamPayload(
				sessionId, name == null ? "" : name, snapshot == null ? "" : snapshot, false));
	}

	/** 该会话完成：把最终完整文本推到浮层并标记 done（客户端去光标并开始淡出）。 */
	public static void finishOverlay(ServerPlayer player, int sessionId, String name, String full) {
		sendStreamPacket(player, new AssistantStreamPayloads.AssistantStreamPayload(
				sessionId, name == null ? "" : name, full == null ? "" : full, true));
	}

	private static void sendStreamPacket(ServerPlayer player,
	                                     AssistantStreamPayloads.AssistantStreamPayload payload) {
		try {
			ServerPlayNetworking.send(player, payload);
		} catch (Exception e) {
			// mock 连接发送会失败：静默
			OpenCraftMod.LOGGER.debug("[OpenCraft] 发送流式浮层包失败（可能是模拟连接）: {}", e.toString());
		}
	}

	/** 给配置界面聊天窗口发送一个 S2C 事件（模拟连接发送失败时静默忽略）。 */
	public static void sendGuiEvent(ServerPlayer player, BlockPos guiBlockPos,
	                                 ResourceKey<Level> guiDimension, String kind, Component text) {
		try {
			ServerPlayNetworking.send(player,
					new AiConfigPayloads.AiConfigChatEventPayload(kind, text, guiBlockPos, guiDimension));
		} catch (Exception e) {
			OpenCraftMod.LOGGER.debug("[OpenCraft] 发送聊天窗口事件({})失败（可能是模拟连接）: {}",
					kind, e.toString());
		}
	}

	/**
	 * 流式显示结束后（服务端线程）收尾（命令模式）：
	 * 清掉 action bar 上的流式残留，以助手名义把完整回复广播到聊天。
	 */
	public static void finishStreamReply(ServerPlayer player, AiAssistant assistant, String full) {
		player.displayClientMessage(Component.empty(), true);
		if (full == null || full.isBlank()) {
			com.swaydy.opencraft.logging.DebugLog.log("chat",
					"助手 {} 回复为空（未广播）", assistant.getConfig().effectiveName());
			return; // 空回复：不广播也不报错
		}
		com.swaydy.opencraft.logging.DebugLog.log("chat",
				"助手 {} 回复玩家 {}: {}", assistant.getConfig().effectiveName(),
				player.getName().getString(), full);
		speakAsAssistant((ServerLevel) player.level(), assistant, full);
	}

	/**
	 * 流式显示结束后（服务端线程）收尾（GUI 模式）：
	 * 回复只以 "reply" 事件回传窗口，不广播到世界聊天（私人会话）。
	 */
	public static void finishGuiReply(ServerPlayer player,
	                                  BlockPos guiBlockPos, ResourceKey<Level> guiDimension, String full) {
		if (full == null || full.isBlank()) {
			sendGuiEvent(player, guiBlockPos, guiDimension, "reply", Component.empty());
			return;
		}
		sendGuiEvent(player, guiBlockPos, guiDimension, "reply", Component.literal(full));
	}

	/** 某方块（即其绑定助手）对话历史的 JSON 快照（[{role, content}, ...]）。 */
	public static String historyJson(GlobalPos block) {
		List<LlmClient.Message> list = block == null ? null : HISTORY.get(block);
		JsonArray arr = new JsonArray();
		if (list != null) {
			for (LlmClient.Message m : list) {
				JsonObject o = new JsonObject();
				o.addProperty("role", m.role().name().toLowerCase(Locale.ROOT));
				o.addProperty("content", m.text());
				arr.add(o);
			}
		}
		return GSON.toJson(arr);
	}

	/** 让助手瞬移到玩家身边（支持跨维度，两种形态通用）。 */
	public static void teleportAssistantToPlayer(ServerPlayer player, AiAssistant assistant) {
		ServerLevel target = (ServerLevel) player.level();
		Vec3 safe = findSafeSpawnPos(target,
				new Vec3(player.getX() + 1.5, player.getY(), player.getZ() + 1.5));
		((net.minecraft.world.entity.Entity) assistant).teleportTo(
				target, safe.x, safe.y, safe.z,
				Set.of(), player.getYRot(), player.getXRot(), true);
		target.playSound(null, assistant.blockPosition(), SoundEvents.ALLAY_AMBIENT_WITH_ITEM,
				SoundSource.AMBIENT, 0.5F, 1.2F);
		com.swaydy.opencraft.logging.DebugLog.log("teleport",
				"助手 {} 传送到玩家 {} 身边（{}, {}, {}）", assistant.getConfig().effectiveName(),
				player.getName().getString(), (int) safe.x, (int) safe.y, (int) safe.z);
		player.sendSystemMessage(Component.translatable("command.opencraft.action.tp.ok"));
	}

	/** 让助手“开口说话”：播放提示音 + 以 [助手名] 前缀广播到聊天。 */
	public static void speakAsAssistant(ServerLevel level, AiAssistant assistant, String text) {
		Component name = assistant.getDisplayName();
		Component message = Component.literal("[").append(name).append("] ")
				.append(Component.literal(text));
		level.getServer().getPlayerList().broadcastSystemMessage(message, false);
		if (assistant.isAlive()) {
			level.playSound(null, assistant.blockPosition(), SoundEvents.ALLAY_AMBIENT_WITH_ITEM,
					SoundSource.AMBIENT, 0.5F, 1.3F);
		}
	}

	// 玩家当前游戏状态上下文（原 buildGameContext 及其专用辅助）已移至 agent/Prompts.playerState——
	// 非插件提示词（Markdown + JSON 数据段）统一集中在该类管理;此处仅保留玩家/助手
	// 共用的 facingName/bearingTo/shortName 等显示工具。

	/** 面向方位：yaw → 东南西北（双形态通用显示）。 */
	public static String facingName(float yaw) {
		int dir = Math.floorMod(Math.round(yaw / 90.0F), 4);
		return switch (dir) {
			case 0 -> "South(+Z)";
			case 1 -> "West(-X)";
			case 2 -> "North(-Z)";
			default -> "East(+X)";
		};
	}

	/** 从 / 到目标的方位（东南西北格数；原地返回 here）。 */
	public static String bearingTo(BlockPos from, BlockPos to) {
		int dx = to.getX() - from.getX();
		int dz = to.getZ() - from.getZ();
		StringBuilder sb = new StringBuilder();
		if (dx > 0) {
			sb.append(dx).append(" east");
		} else if (dx < 0) {
			sb.append(-dx).append(" west");
		}
		if (dz > 0) {
			sb.append(dz).append(" south");
		} else if (dz < 0) {
			sb.append(-dz).append(" north");
		}
		return sb.length() == 0 ? "here" : sb.toString();
	}

	/** 描述键后缀短名（block.minecraft.stone → stone）；null/异常返回 ?。 */
	public static String shortName(String key) {
		if (key == null) {
			return "?";
		}
		int idx = key.lastIndexOf('.');
		return idx < 0 ? key : key.substring(idx + 1);
	}

	/**
	 * 从偏好位置向上扫描，找一个安全的出生点：
	 * 该格及其上方一格是空气，且脚下（下方一格）是实体方块。
	 * 找不到就返回偏好位置。
	 */
	public static Vec3 findSafeSpawnPos(ServerLevel level, Vec3 preferred) {
		BlockPos base = BlockPos.containing(preferred);
		for (int dy = 0; dy <= 12; dy++) {
			BlockPos pos = base.above(dy);
			if (isSafeSpawnBlock(level, pos)) {
				return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
			}
		}
		return preferred;
	}

	private static boolean isSafeSpawnBlock(ServerLevel level, net.minecraft.core.BlockPos pos) {
		return level.getBlockState(pos).isAir()
				&& level.getBlockState(pos.above()).isAir()
				&& !level.getBlockState(pos.below()).isAir();
	}

	/**
	 * 将物品 ID 解析为 Item 的 Holder（供 Inventory/Hand/Craft 工具用）；不存在返回 null。
	 *
	 * <p><b>容错解析</b>（工具给大模型的物品清单是“短名”，模型会照抄回填参数，必须能对上）：
	 * - 完整 ID（含命名空间）：{@code minecraft:stone} / {@code opencraft:ai_logo_block}；
	 * - 裸名/短名：{@code stone} → {@code minecraft:stone}，{@code ai_logo_block} →
	 *   {@code opencraft:ai_logo_block}（先试 minecraft 命名空间，再试本模组命名空间）；
	 * - 描述名兜底：{@code block.opencraft.ai_logo_block} / {@code item.minecraft.stick}
	 *   → 取最后一个点后的短名再按上一条解析。
	 */
	public static Holder<net.minecraft.world.item.Item> resolveItem(String itemId) {
		if (itemId == null || itemId.isBlank()) {
			return null;
		}
		String s = itemId.trim().toLowerCase(java.util.Locale.ROOT);
		Holder<net.minecraft.world.item.Item> resolved = tryResolveItem(s);
		if (resolved != null) {
			return resolved;
		}
		// 描述名兜底：block.opencraft.ai_logo_block / item.minecraft.stick → 取最后一段短名
		int dot = s.lastIndexOf('.');
		if (dot >= 0 && dot < s.length() - 1) {
			resolved = tryResolveItem(s.substring(dot + 1));
		}
		return resolved;
	}

	/** 解析单个候选：裸名先原样试、再补 minecraft: / opencraft: 前缀；全部失败返回 null。 */
	private static Holder<net.minecraft.world.item.Item> tryResolveItem(String id) {
		if (id == null || id.isBlank()) {
			return null;
		}
		java.util.List<String> candidates = new java.util.ArrayList<>(3);
		candidates.add(id);
		if (!id.contains(":")) {
			candidates.add("minecraft:" + id);
			candidates.add(OpenCraftMod.MOD_ID + ":" + id);
		}
		for (String candidate : candidates) {
			try {
				Identifier identifier = Identifier.parse(candidate);
				var opt = BuiltInRegistries.ITEM.get(identifier);
				if (!opt.isEmpty()) {
					return opt.get();
				}
			} catch (Exception ignored) {
				// 非法 ID 形状：试下一个候选
			}
		}
		return null;
	}

	/** 解析方块：完整 id / 短名 / 描述名后缀 → {@code BuiltInRegistries.BLOCK}；失败返回 null。 */
	public static Holder<net.minecraft.world.level.block.Block> resolveBlock(String blockId) {
		if (blockId == null || blockId.isBlank()) {
			return null;
		}
		String s = blockId.trim().toLowerCase(java.util.Locale.ROOT);
		Holder<net.minecraft.world.level.block.Block> resolved = tryResolveBlock(s);
		if (resolved != null) {
			return resolved;
		}
		// 描述名兜底：block.minecraft.oak_log → oak_log
		int dot = s.lastIndexOf('.');
		if (dot >= 0 && dot < s.length() - 1) {
			resolved = tryResolveBlock(s.substring(dot + 1));
		}
		return resolved;
	}

	/** 解析单个候选：裸名先原样试、再补 minecraft: / opencraft: 前缀；全部失败返回 null。 */
	private static Holder<net.minecraft.world.level.block.Block> tryResolveBlock(String id) {
		if (id == null || id.isBlank()) {
			return null;
		}
		java.util.List<String> candidates = new java.util.ArrayList<>(3);
		candidates.add(id);
		if (!id.contains(":")) {
			candidates.add("minecraft:" + id);
			candidates.add(OpenCraftMod.MOD_ID + ":" + id);
		}
		for (String candidate : candidates) {
			try {
				Identifier identifier = Identifier.parse(candidate);
				var opt = BuiltInRegistries.BLOCK.get(identifier);
				if (!opt.isEmpty()) {
					return opt.get();
				}
			} catch (Exception ignored) {
				// 非法 ID 形状：试下一个候选
			}
		}
		return null;
	}
}