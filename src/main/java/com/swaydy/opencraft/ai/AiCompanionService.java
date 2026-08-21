package com.swaydy.opencraft.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.swaydy.opencraft.OpenCraftMod;
import com.swaydy.opencraft.agent.AgentRegistry;
import com.swaydy.opencraft.agent.AgentRuntime;
import com.swaydy.opencraft.assistant.AiAssistant;
import com.swaydy.opencraft.assistant.AssistantFacade;
import com.swaydy.opencraft.assistant.player.PlayerAssistantService;
import com.swaydy.opencraft.block.ModBlocks;
import com.swaydy.opencraft.entity.AiAssistantEntity;
import com.swaydy.opencraft.entity.ModEntities;
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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
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

	/**
	 * 最近召唤的助手缓存，按绑定方块键控。
	 * 实体加入世界的查找表是异步的（PersistentEntitySectionManager 的
	 * loadingInbox 下一 tick 才生效），同一 tick 内再次召唤可能查不到
	 * 刚生成的实体；这里缓存刚召唤的实例，避免同 tick 重复召唤。
	 */
	private static final Map<GlobalPos, AiAssistantEntity> RECENT_SUMMONS = new ConcurrentHashMap<>();

	private AiCompanionService() {
	}

	/** 在模组初始化时注册服务器生命周期回调。 */
	public static void init() {
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			HISTORY.clear();
			RECENT_SUMMONS.clear();
			STREAM_SESSIONS.clear();
			EXECUTOR.shutdown();
			AgentRuntime.shutdown();
		});
	}

	// ------------------------------------------------------------------
	// 对外接口
	// ------------------------------------------------------------------

	/**
	 * 让玩家问助手一个问题（异步）。目标是玩家“最近”的助手（按绑定方块距离，实体/玩家形态都算）；
	 * 还没有任何助手时自动召唤一个（按形态路由，绑定最近的未绑定方块）。每个助手有独立记忆。
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
		com.swaydy.opencraft.debug.DebugLog.log("chat",
				"玩家 {} 问助手 {}（方块 {}）: {}", player.getName().getString(),
				assistant.getConfig().effectiveName(),
				historyKey == null ? "?" : historyKey.pos().toShortString(), question);
		AgentRuntime.runAsync(player, assistant, question, historyKey, null, null);
	}

	/**
	 * 配置界面聊天窗口 / 右键互动界面：向【指定】助手提问（异步），回复通过 S2C
	 * 事件回传窗口，不广播到世界聊天（私人会话）。与 {@link #ask(ServerPlayer,
	 * AiAssistantEntity, String)} 共享同一份对话历史与 agentic loop。
	 *
	 * @param guiBlockPos / guiDimension 非空时表示 GUI 模式：流式增量以 "delta" 事件
	 *                     推送到客户端窗口，结束时以 "reply" 事件回传完整回复。
	 */
	public static void askGui(ServerPlayer player, AiAssistant assistant, String question,
	                          BlockPos guiBlockPos, ResourceKey<Level> guiDimension) {
		GlobalPos historyKey = historyKeyFor(assistant);
		com.swaydy.opencraft.debug.DebugLog.log("chat",
				"玩家 {} 通过界面问助手 {}（方块 {}）: {}", player.getName().getString(),
				assistant.getConfig().effectiveName(),
				historyKey == null ? "?" : historyKey.pos().toShortString(), question);
		AgentRuntime.runAsync(player, assistant, question, historyKey, guiBlockPos, guiDimension);
	}

	/**
	 * 召唤（或找到）玩家最近的助手：自动绑定最近的、尚未被绑定的 AI 徽标方块。
	 *
	 * <p>**设计约定**：AI 助手本身就是“像多人联机客户端一样加入游戏”的真玩家
	 * （{@link com.swaydy.opencraft.assistant.player.AiAssistantPlayer}，经
	 * PlayerList.placeNewPlayer 正式进服）——召唤一律走玩家形态，与 Agent 预设无关
	 * （预设只决定 LLM 行为）。旧存档遗留的实体形态助手由 {@link AssistantFacade#summon} 迁移。
	 */
	public static AiAssistant summonFor(ServerPlayer player) {
		GlobalPos block = AiConfigHandler.findNearestConfigBlock(
				(ServerLevel) player.level(), player.blockPosition(), 48, true);
		if (block == null) {
			return null;
		}
		return summonFor(player, block);
	}

	/**
	 * 召唤（或找到）绑定到指定 AI 徽标方块的助手：**一律玩家形态**（真正的 ServerPlayer bot，
	 * 像客户端一样进服），与方块配置的 Agent 预设无关（预设只决定 LLM 行为）。
	 *
	 * 多助手规则（每个 AI 徽标方块至多绑定一个助手，跨形态统一判定）：
	 * - 目标方块已有玩家形态助手绑定：是该玩家的 → 直接返回（幂等）；是别人的 → 返回 null（拒绝）；
	 * - 目标方块未被绑定 → 新建一个绑定该方块的玩家形态助手，归召唤者所有。
	 */
	public static AiAssistant summonFor(ServerPlayer player, GlobalPos explicitConfigBlock) {
		return AssistantFacade.summon(player, explicitConfigBlock);
	}

	/**
	 * 【遗留】实体形态召唤（PathfinderMob 底座）：旧实现，仅用于兼容旧存档/回归测试。
	 * 新设计下助手一律是玩家形态（见 {@link #summonFor(ServerPlayer, GlobalPos)}）；
	 * 本方法不再被任何用户路径调用。
	 */
	public static AiAssistantEntity summonLegacyEntityFor(ServerPlayer player) {
		return summonEntityFor(player, null);
	}

	/** 【遗留】同 {@link #summonLegacyEntityFor(ServerPlayer)}，显式指定绑定方块。 */
	public static AiAssistantEntity summonLegacyEntityFor(ServerPlayer player, GlobalPos explicitConfigBlock) {
		return summonEntityFor(player, explicitConfigBlock);
	}

	/**
	 * 实体形态召唤（PathfinderMob 底座）的实现。
	 * explicitConfigBlock 为 null 时自动找最近的未绑定方块。
	 */
	private static AiAssistantEntity summonEntityFor(ServerPlayer player, GlobalPos explicitConfigBlock) {
		ServerLevel level = (ServerLevel) player.level();
		GlobalPos configBlock;
		if (explicitConfigBlock != null) {
			ServerLevel blkLevel = level.getServer().getLevel(explicitConfigBlock.dimension());
			if (blkLevel == null
					|| !blkLevel.getBlockState(explicitConfigBlock.pos()).is(ModBlocks.AI_LOGO_BLOCK)) {
				OpenCraftMod.LOGGER.info("[OpenCraft] 拒绝召唤：指定的 AI 徽标方块不存在或已被移除");
				com.swaydy.opencraft.debug.DebugLog.log("summon",
						"拒绝召唤：玩家 {} 指定的 AI 徽标方块不存在（{}）",
						player.getName().getString(), explicitConfigBlock.pos().toShortString());
				return null;
			}
			// 该方块已绑定助手：是自己的 → 幂等返回；是别人的 → 拒绝（一方块一助手）
			AiAssistantEntity bound = ModEntities.findAssistantBoundTo(level, explicitConfigBlock);
			if (bound != null) {
				if (bound.getOwnerUuid() != null && bound.getOwnerUuid().equals(player.getUUID())) {
					return bound;
				}
				OpenCraftMod.LOGGER.info("[OpenCraft] 拒绝召唤：AI 徽标方块({})已被另一个助手绑定",
						explicitConfigBlock.pos().toShortString());
				com.swaydy.opencraft.debug.DebugLog.log("summon",
						"拒绝召唤：方块 {} 已被其他玩家的助手绑定", explicitConfigBlock.pos().toShortString());
				return null;
			}
			configBlock = explicitConfigBlock;
		} else {
			configBlock = AiConfigHandler.findNearestConfigBlock(level, player.blockPosition(), 48, true);
			if (configBlock == null) {
				OpenCraftMod.LOGGER.info("[OpenCraft] 拒绝召唤：附近 48 格内没有未绑定的 AI 徽标方块");
				com.swaydy.opencraft.debug.DebugLog.log("summon",
						"拒绝召唤：玩家 {} 附近 48 格内没有未绑定的 AI 徽标方块",
						player.getName().getString());
				return null;
			}
		}
		// 同一 tick 内刚召唤过（实体查找表异步生效）：直接返回缓存实例
		AiAssistantEntity recent = RECENT_SUMMONS.get(configBlock);
		if (recent != null && recent.isAlive() && !recent.isRemoved()) {
			if (recent.getOwnerUuid() != null && recent.getOwnerUuid().equals(player.getUUID())) {
				return recent;
			}
			return null; // 同一 tick 内被其他玩家占用了
		}
		AiAssistantEntity assistant = new AiAssistantEntity(ModEntities.AI_ASSISTANT, level);
		// 找一个安全位置：优先玩家旁边，向上扫描最多 12 格找“脚下有实体的空气格”
		Vec3 spawnPos = findSafeSpawnPos(level,
				new Vec3(player.getX() + 1.5, player.getY(), player.getZ() + 1.5));
		assistant.setPos(spawnPos.x, spawnPos.y, spawnPos.z);
		assistant.setOwner(player);
		assistant.setConfigBlock(configBlock);
		// 显示名 = 绑定方块配置的名字（含坐标）；服务端 getDisplayName 会实时取配置
		assistant.setCustomName(assistant.getDisplayName());
		if (level.addFreshEntity(assistant)) {
			RECENT_SUMMONS.put(configBlock, assistant);
			// 绑定方块亮起（激活状态 = 有助手绑定）
			AiConfigHandler.syncBoundBlockPoweredState(
					player.level().getServer().getLevel(configBlock.dimension()), configBlock);
			level.playSound(null, assistant.blockPosition(), SoundEvents.ALLAY_AMBIENT_WITHOUT_ITEM,
					SoundSource.AMBIENT, 0.6F, 1.1F);
			OpenCraftMod.LOGGER.info("[OpenCraft] 玩家 {} 召唤了绑定方块({})的 AI 助手",
					player.getName().getString(), configBlock.pos().toShortString());
			com.swaydy.opencraft.debug.DebugLog.log("summon",
					"玩家 {} 召唤了助手（名字 {}，绑定方块 {}，出生点 ({},{},{})）",
					player.getName().getString(), assistant.getConfig().effectiveName(),
					configBlock.pos().toShortString(),
					(int) spawnPos.x, (int) spawnPos.y, (int) spawnPos.z);
			// 新助手第一次出现：让它打个招呼
			greetNewAssistant(player, assistant);
			return assistant;
		}
		return null;
	}

	/** 对话历史的键：助手绑定的方块（每个方块一个助手、一份记忆）。 */
	private static GlobalPos historyKeyFor(AiAssistant assistant) {
		GlobalPos block = assistant.getConfigBlock();
		if (block != null) {
			return block;
		}
		return GlobalPos.of(assistant.level().dimension(), assistant.blockPosition());
	}

	/** 新助手被召唤时打个招呼（方块配置可用则用大模型生成，否则用预设欢迎语）。 */
	private static void greetNewAssistant(ServerPlayer player, AiAssistant assistant) {
		AiBlockConfig config = assistant.getConfig();
		ServerLevel level = (ServerLevel) player.level();
		if (!config.isUsable()) {
			speakAsAssistant(level, assistant,
					Component.translatable("entity.opencraft.ai_assistant.greeting_canned").getString());
			return;
		}
		List<LlmClient.Message> messages = List.of(
				LlmClient.Message.user("(You were just summoned by the player. Greet them warmly in one or two sentences, "
						+ "briefly introduce yourself, and tell them they can chat with you using /opencraft ask; no need to act on the world.)"));
		LlmClient.Request request = new LlmClient.Request(
				config.baseUrl,
				config.apiKey,
				config.model,
				AgentRuntime.buildPersona(config, AgentRegistry.resolveAgent(config)),
				messages,
				null,
				Math.min(0.9, config.temperature),
				null, null,
				config.timeoutSeconds);
		// 流式打招呼：增量显示在召唤者的 action bar，结束后以助手名义广播完整问候
		// （historyKey 传 null：问候语不写入对话记忆）
		streamPlain(player, assistant, request);
	}

	/** 送走玩家“最近”的助手（按绑定方块距离）；没有可送走的则返回 false。 */
	public static boolean dismissFor(ServerPlayer player) {
		AiAssistantEntity assistant = ModEntities.findNearestAssistantFor(player);
		if (assistant == null) {
			return false;
		}
		dismissAssistant(assistant);
		return true;
	}

	/**
	 * 送走绑定到指定 AI 徽标方块的助手（配置界面“不召唤”按钮）：
	 * 该方块没有绑定助手时返回 false（视为已是“未召唤”状态）。
	 */
	public static boolean dismissBoundTo(ServerLevel anyLevel, GlobalPos blockPos) {
		if (anyLevel == null || blockPos == null) {
			return false;
		}
		AiAssistantEntity assistant = ModEntities.findAssistantBoundTo(anyLevel, blockPos);
		if (assistant == null) {
			return false;
		}
		dismissAssistant(assistant);
		return true;
	}

	/** 送走玩家的全部助手；没有任何助手则返回 false。 */
	public static boolean dismissAllFor(ServerPlayer player) {
		List<AiAssistantEntity> owned = ModEntities.findAssistantsFor(player);
		if (owned.isEmpty()) {
			return false;
		}
		for (AiAssistantEntity assistant : owned) {
			dismissAssistant(assistant);
		}
		return true;
	}

	/**
	 * 送走【指定】助手实体（右键互动界面的“送走”按钮）。
	 * 只送走仍然存活、仍绑定配置方块的助手；已消失/已送走则返回 false（幂等）。
	 */
	public static boolean dismissAssistantEntity(AiAssistantEntity assistant) {
		if (assistant == null || assistant.isRemoved() || !assistant.isAlive()) {
			return false;
		}
		dismissAssistant(assistant);
		return true;
	}

	/**
	 * 按实体 ID 解析“属于该玩家的助手”，用于右键互动界面发来的请求：
	 * 实体必须存在于玩家当前维度、是 AI 助手、且主人是该玩家，否则返回 null
	 * （服务端每次请求都重新校验，不信任客户端）。
	 */
	public static AiAssistantEntity resolveOwnedAssistant(ServerPlayer player, int entityId) {
		if (player == null) {
			return null;
		}
		net.minecraft.world.entity.Entity entity = player.level().getEntity(entityId);
		if (!(entity instanceof AiAssistantEntity assistant)) {
			return null;
		}
		UUID ownerUuid = assistant.getOwnerUuid();
		if (ownerUuid == null || !ownerUuid.equals(player.getUUID())) {
			return null;
		}
		return assistant;
	}

	private static void dismissAssistant(AiAssistantEntity assistant) {
		assistant.cancelCurrentTask();
		GlobalPos configBlock = assistant.getConfigBlock();
		assistant.discard();
		com.swaydy.opencraft.debug.DebugLog.log("summon",
				"送走助手（名字 {}，绑定方块 {}）",
				assistant.getConfig().effectiveName(),
				configBlock == null ? "?" : configBlock.pos().toShortString());
		// AiAssistantEntity.remove 会兜底熄灭绑定方块（若没有其他助手仍绑定它）
		if (configBlock != null) {
			RECENT_SUMMONS.remove(configBlock);
		}
	}

	/** 清空某个方块（即该方块绑定的助手）的对话历史。 */
	public static void resetHistory(GlobalPos block) {
		if (block != null) {
			HISTORY.remove(block);
			com.swaydy.opencraft.debug.DebugLog.log("history", "清空了方块 {} 的对话历史",
					block.pos().toShortString());
		}
	}

	/** 清空玩家全部助手的对话历史。 */
	public static void resetAllHistory(ServerPlayer player) {
		for (AiAssistantEntity assistant : ModEntities.findAssistantsFor(player)) {
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

	/** 把流式回复的当前进度显示到玩家的 action bar（overlay，可被下一次更新覆盖）。 */
	public static void showStreamingText(ServerPlayer player, String text) {
		// action bar 是单行渲染：把换行/连续空白折叠成单个空格，避免排版错乱；
		// 末尾加 ▍ 光标提示“正在生成中”
		String preview = text.replaceAll("\\s+", " ").trim();
		player.displayClientMessage(Component.literal(preview + "▍"), true);
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
			com.swaydy.opencraft.debug.DebugLog.log("chat",
					"助手 {} 回复为空（未广播）", assistant.getConfig().effectiveName());
			return; // 空回复：不广播也不报错
		}
		com.swaydy.opencraft.debug.DebugLog.log("chat",
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

	/** 挖掘掉落物进入主人背包时给主人的提示（可选，静默失败）。 */
	public static void notifyInventoryGain(ServerPlayer owner, ItemStack stack) {
		if (owner == null || stack == null || stack.isEmpty()) {
			return;
		}
		try {
			owner.displayClientMessage(Component.translatable(
					"command.opencraft.action.give.ok", stack.getCount(),
					stack.getHoverName().getString()), true);
		} catch (Exception e) {
			// 静默：提示失败不影响掉落
		}
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

	// ------------------------------------------------------------------
	// 打招呼用的简易流式（无工具；打字机 reveal + 广播）
	// ------------------------------------------------------------------

	private static final long FLUSH_INTERVAL_MS = 80L;
	private static final int LIVE_REVEAL_CHARS = 8;
	private static final int MAX_REVEAL_FLUSHES = 60;

	/** 一次性纯文本流式回复（用于打招呼，不涉及工具循环）。 */
	private static void streamPlain(ServerPlayer player, AiAssistant assistant,
	                                LlmClient.Request request) {
		MinecraftServer server = player.level().getServer();
		int sessionId = nextStreamSession(player);
		final String chatName = safeChatName(assistant);
		StringBuilder buffer = new StringBuilder();
		long[] lastFlushAt = {0L};
		int[] revealed = {0};

		LlmClient.stream(request, new LlmClient.ChunkSink() {
			@Override
			public void onChunk(LlmClient.Chunk chunk) {
				if (chunk instanceof LlmClient.TextDelta td) {
					if (td.text() == null || td.text().isEmpty()) {
						return;
					}
					buffer.append(td.text());
					maybeReveal(false);
				} else if (chunk instanceof LlmClient.Finish f) {
					if (f.ok()) {
						String full = buffer.toString();
						// 剩余文本 reveal + 广播交给独立异步任务（不阻塞 SSE 读取线程）
						CompletableFuture.runAsync(() -> {
							while (revealed[0] < buffer.length()) {
								maybeReveal(true);
								if (revealed[0] >= buffer.length()) {
									break;
								}
								try {
									Thread.sleep(FLUSH_INTERVAL_MS);
								} catch (InterruptedException e) {
									Thread.currentThread().interrupt();
									break;
								}
							}
							// 兜底：保证浮层一定收到 done（含空回复/已由收尾 reveal 标记 done 的重复发）
							finishOverlay(player, sessionId, chatName, full);
							runOnServer(server, () -> finishStreamReply(player, assistant, full));
						}, EXECUTOR);
					} else {
						String reason = f.failure() == null ? "未知错误"
								: (f.failure().message() == null || f.failure().message().isBlank()
										? f.failure().code() : f.failure().message());
						runOnServer(server, () -> player.sendSystemMessage(
								Component.translatable("command.opencraft.ask.error", reason)));
					}
				}
			}

			private void maybeReveal(boolean finalizing) {
				long now = System.currentTimeMillis();
				if (!finalizing && now - lastFlushAt[0] < FLUSH_INTERVAL_MS) {
					return;
				}
				int len = buffer.length();
				if (revealed[0] >= len) {
					return;
				}
				int step = finalizing
						? Math.max(LIVE_REVEAL_CHARS,
								(len - revealed[0] + MAX_REVEAL_FLUSHES - 1) / MAX_REVEAL_FLUSHES)
						: LIVE_REVEAL_CHARS;
				int target = Math.min(len, revealed[0] + step);
				revealed[0] = target;
				lastFlushAt[0] = now;
				String snapshot = buffer.substring(0, target);
				boolean last = finalizing && target >= len;
				streamOverlay(player, sessionId, chatName, snapshot);
				if (last) {
					finishOverlay(player, sessionId, chatName, snapshot);
				}
			}
		});
	}

	/** 浮层/状态用的助手名（配置名；读不到时回退空串）。 */
	private static String safeChatName(AiAssistant assistant) {
		try {
			String n = assistant == null ? "" : assistant.getConfig().effectiveName();
			return n == null ? "" : n;
		} catch (Exception e) {
			return "";
		}
	}

	/** 把任务调度回服务端线程执行；服务器已停止时静默丢弃并记日志。 */
	private static void runOnServer(MinecraftServer server, Runnable task) {
		try {
			server.executeIfPossible(task);
		} catch (Exception e) {
			OpenCraftMod.LOGGER.warn("[OpenCraft] 无法调度到服务端线程: {}", e.toString());
		}
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
		com.swaydy.opencraft.debug.DebugLog.log("teleport",
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

	/**
	 * 构造“玩家当前游戏状态 + 环境”上下文，帮助模型给出贴合游戏的回答。
	 * 每轮请求都会重建（见 {@code AgentRuntime.runRound} 对 messages[0] 的刷新），
	 * 因此位置/天气/装备等都是最新的，不是提问那一刻的静态快照。
	 */
	public static String buildGameContext(ServerPlayer player) {
		try {
			Level level = player.level();
			long dayTime = level.getDayTime();
			long day = dayTime / 24000 + 1;
			long timeOfDay = dayTime % 24000;
			int hour = (int) ((timeOfDay / 1000 + 6) % 24);
			int minute = (int) ((timeOfDay % 1000) / 100 * 6);
			BlockPos pos = player.blockPosition();
			String phase = phaseOfTime(timeOfDay);
			ItemStack mainHand = player.getMainHandItem();
			String itemName = mainHand.isEmpty() ? "empty hand" : mainHand.getHoverName().getString();

			// 身体状态：水下/氧气/着火
			String body = "normal";
			if (player.isUnderWater()) {
				body = "underwater (air " + player.getAirSupply() + "/" + player.getMaxAirSupply() + ")";
			} else if (player.getAirSupply() < player.getMaxAirSupply()) {
				body = "air " + player.getAirSupply() + "/" + player.getMaxAirSupply();
			}
			if (player.isOnFire()) {
				body += ", on fire";
			}

			return String.format("""
					[Player's current game state]
					Player name: %s
					Dimension: %s
					Position: x=%d, y=%d, z=%d (facing %s)
					%s
					Time: Day %d %02d:%02d (%s)
					Health: %.0f/20 | Hunger: %d/20 | XP level: %d | Game mode: %s
					Body: %s
					Effects: %s
					Equipment: %s
					Looking at: %s
					Main hand: %s
					""",
					player.getName().getString(),
					level.dimension().identifier(),
					pos.getX(), pos.getY(), pos.getZ(), facingName(player.getYRot()),
					environmentCapsule(level, pos, 16),
					day, hour, minute, phase,
					player.getHealth(),
					player.getFoodData().getFoodLevel(),
					player.experienceLevel,
					player.gameMode.getGameModeForPlayer().name(),
					body,
					effectsSummary(player),
					equipmentSummary(player),
					lookingAt(level, player),
					itemName);
		} catch (Exception e) {
			return "[Player's current game state] unavailable";
		}
	}

	/** 一天内时刻（24000 tick）→ 时段名。 */
	private static String phaseOfTime(long timeOfDay) {
		if (timeOfDay < 6000) {
			return "dawn";
		}
		if (timeOfDay < 11000) {
			return "day";
		}
		if (timeOfDay < 13000) {
			return "dusk";
		}
		if (timeOfDay < 18000) {
			return "night";
		}
		return "deep night";
	}

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

	/** 装备摘要（36 主背包之后的装备/副手/身体/坐骑鞍槽位所戴物品）；无装备返回「无」。 */
	static String equipmentSummary(ServerPlayer player) {
		net.minecraft.world.entity.player.Inventory inv = player.getInventory();
		StringBuilder sb = new StringBuilder();
		for (int i = 36; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) {
				continue;
			}
			if (sb.length() > 0) {
				sb.append(", ");
			}
			sb.append(shortName(s.getItem().getDescriptionId())).append("(").append(equipmentSlotLabel(i)).append(")");
		}
		return sb.length() == 0 ? "none" : sb.toString();
	}

	private static String equipmentSlotLabel(int index) {
		return switch (index) {
			case 36 -> "feet";
			case 37 -> "legs";
			case 38 -> "chest";
			case 39 -> "head";
			case 40 -> "offhand";
			case 41 -> "body";
			case 42 -> "saddle";
			default -> "equipment";
		};
	}

	/** 状态效果摘要（最多 3 个，含 等级(时长s)）；无效果返回「无」。 */
	static String effectsSummary(ServerPlayer player) {
		List<MobEffectInstance> effects = new ArrayList<>(player.getActiveEffects());
		if (effects.isEmpty()) {
			return "none";
		}
		StringBuilder sb = new StringBuilder();
		int n = 0;
		for (MobEffectInstance e : effects) {
			if (n >= 3) {
				sb.append("…");
				break;
			}
			if (n > 0) {
				sb.append(", ");
			}
			sb.append(shortName(e.getEffect().value().getDescriptionId()));
			int amp = e.getAmplifier();
			if (amp > 0) {
				sb.append(romanNumeral(amp + 1));
			}
			sb.append("(").append(Math.max(1, e.getDuration() / 20)).append("s)");
			n++;
		}
		return sb.toString();
	}

	private static String romanNumeral(int x) {
		return switch (x) {
			case 2 -> "II";
			case 3 -> "III";
			case 4 -> "IV";
			default -> "";
		};
	}

	/** 玩家视线命中的方块（10 格射线，含距离）；未命中返回「无」。 */
	static String lookingAt(Level level, ServerPlayer player) {
		try {
			Vec3 eye = player.getEyePosition();
			Vec3 look = player.getLookAngle();
			BlockHitResult hit = level.clip(new ClipContext(
					eye, eye.add(look.scale(10.0)),
					ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
			if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
				return "none";
			}
			double dist = Math.round(player.position().distanceTo(hit.getLocation()) * 10.0) / 10.0;
			return shortName(level.getBlockState(hit.getBlockPos()).getBlock().getDescriptionId())
					+ " (" + dist + " blocks away)";
		} catch (Exception e) {
			return "none";
		}
	}

	/**
	 * 紧凑环境摘要（群系/气候/降水/天气/亮度/脚下方块/附近怪物）：
	 * {@code 群系: 平原（温和/有降水） | 天气: 晴朗 | 亮度: 大晴 | 脚下: grass_block | 附近怪物: 0}。
	 * 玩家状态与助手自身状态共用，不重复实现。
	 *
	 * @param hostileRadius 附近怪物统计半径；<1 表示不统计
	 */
	public static String environmentCapsule(Level level, BlockPos pos, int hostileRadius) {
		try {
			Holder<Biome> biome = level.getBiome(pos);
			StringBuilder sb = new StringBuilder();
			float temp = biome.value().getBaseTemperature();
			String climate = temp < 0.2 ? "cold" : temp > 0.9 ? "hot" : "mild";
			String precip = biome.value().hasPrecipitation() ? "rainy" : "dry";
			sb.append("Environment: biome ").append(biomeName(biome))
					.append(" (").append(climate).append("/").append(precip).append(")");
			sb.append(" | weather: ").append(level.isThundering() ? "thunderstorm"
					: level.isRaining() ? "raining" : "clear");
			int sky = level.getSkyDarken();
			sb.append(" | brightness: ").append(sky == 0 ? "bright"
					: sky >= 15 ? "dark" : "dim(" + sky + ")");
			sb.append(" | ground: ")
					.append(shortName(level.getBlockState(pos.below()).getBlock().getDescriptionId()));
			if (hostileRadius > 0) {
				int hostiles = level.getEntities((net.minecraft.world.entity.Entity) null,
						new AABB(pos).inflate(hostileRadius),
						e -> e instanceof net.minecraft.world.entity.monster.Monster).size();
				sb.append(" | nearby monsters: ").append(hostiles);
			}
			return sb.toString();
		} catch (Exception e) {
			return "environment unavailable";
		}
	}

	private static String biomeName(Holder<Biome> biome) {
		try {
			return biome.unwrapKey()
					.map(k -> k.identifier().getPath().replace('_', ' '))
					.orElse(biome.getRegisteredName());
		} catch (Exception e) {
			return "unknown";
		}
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