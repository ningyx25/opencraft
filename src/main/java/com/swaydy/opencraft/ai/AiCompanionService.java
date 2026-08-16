package com.swaydy.opencraft.ai;

import com.swaydy.opencraft.OpenCraftMod;
import com.swaydy.opencraft.block.ModBlocks;
import com.swaydy.opencraft.entity.AiAssistantEntity;
import com.swaydy.opencraft.entity.ModEntities;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 助手服务：负责
 * - 召唤/移除玩家绑定的助手实体；
 * - 把玩家的问题发给大模型，并把回复以“助手开口说话”的形式广播到游戏聊天；
 * - 为每个玩家维护独立的对话历史（含上下文裁剪）。
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

	/**
	 * 流式回复刷新到玩家 action bar 的最小间隔（毫秒）。
	 * SSE 增量通常很小且很密集，按时间节流合并后再上屏，
	 * 避免每个 token 都向玩家推送一次网络包。
	 */
	private static final long FLUSH_INTERVAL_MS = 150L;

	/**
	 * 对话历史，按“助手绑定的 AI 徽标方块”键控（每个方块至多一个助手，
	 * 因此一个方块 = 一个助手 = 一份独立记忆；送走再召唤同一方块时记忆仍在）。
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
			EXECUTOR.shutdown();
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
		AiAssistantEntity target = ModEntities.findNearestAssistantFor(player);
		if (target == null) {
			// 便利：还没有助手时自动召唤一个
			target = summonFor(player);
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
	public static void ask(ServerPlayer player, AiAssistantEntity assistant, String question) {
		if (assistant == null) {
			player.sendSystemMessage(Component.translatable("command.opencraft.ask.no_assistant"));
			return;
		}
		AiBlockConfig config = assistant.getConfig();
		if (!config.isUsable()) {
			player.sendSystemMessage(Component.translatable("command.opencraft.ask.no_config"));
			return;
		}
		GlobalPos historyKey = historyKeyFor(assistant);

		List<LlmClient.Message> history =
				HISTORY.computeIfAbsent(historyKey, k -> new ArrayList<>());
		history.add(LlmClient.Message.user(question));

		// 组装消息：系统提示词合并为单条（人设 + 游戏状态）——部分严格接口
		// （vLLM/Qwen 等）只允许一条 system 且必须在消息列表开头；
		// 再追加最近的对话历史（历史里只有 user/assistant）。
		List<LlmClient.Message> messages = new ArrayList<>();
		messages.add(LlmClient.Message.system(
				config.effectiveSystemPrompt() + "\n\n" + buildGameContext(player)));
		messages.addAll(LlmClient.trimHistory(history, config.maxHistoryMessages));

		LlmClient.Request request = new LlmClient.Request(
				config.baseUrl,
				config.apiKey,
				config.model,
				config.temperature,
				messages,
				config.timeoutSeconds);

		// 提示玩家助手正在思考（流式回复会逐步覆盖这行提示）
		player.displayClientMessage(Component.translatable("command.opencraft.ask.thinking"), true);

		// 流式回复：增量文本实时显示在玩家的 action bar，结束后广播完整回复
		streamReply(player, assistant, request, historyKey);
	}

	/** 召唤（或找到）玩家最近的助手：自动绑定最近的、尚未被绑定的 AI 徽标方块。 */
	public static AiAssistantEntity summonFor(ServerPlayer player) {
		return summonFor(player, null);
	}

	/**
	 * 召唤（或找到）绑定到指定 AI 徽标方块的助手。
	 *
	 * 多助手规则（每个 AI 徽标方块至多绑定一个助手）：
	 * - 目标方块已有助手绑定：是该玩家的 → 直接返回（幂等）；是别人的 → 返回 null（拒绝）；
	 * - 目标方块未被绑定 → 新建一个绑定该方块的助手，归召唤者所有。
	 * - explicitConfigBlock 为 null 时：自动找最近的未绑定方块，找不到则返回 null（拒绝召唤）。
	 */
	public static AiAssistantEntity summonFor(ServerPlayer player, GlobalPos explicitConfigBlock) {
		ServerLevel level = (ServerLevel) player.level();
		GlobalPos configBlock;
		if (explicitConfigBlock != null) {
			ServerLevel blkLevel = level.getServer().getLevel(explicitConfigBlock.dimension());
			if (blkLevel == null
					|| !blkLevel.getBlockState(explicitConfigBlock.pos()).is(ModBlocks.AI_LOGO_BLOCK)) {
				OpenCraftMod.LOGGER.info("[OpenCraft] 拒绝召唤：指定的 AI 徽标方块不存在或已被移除");
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
				return null;
			}
			configBlock = explicitConfigBlock;
		} else {
			configBlock = AiConfigHandler.findNearestConfigBlock(level, player.blockPosition(), 48, true);
			if (configBlock == null) {
				OpenCraftMod.LOGGER.info("[OpenCraft] 拒绝召唤：附近 48 格内没有未绑定的 AI 徽标方块");
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
			// 新助手第一次出现：让它打个招呼
			greetNewAssistant(player, assistant);
			return assistant;
		}
		return null;
	}

	/** 对话历史的键：助手绑定的方块（每个方块一个助手、一份记忆）。 */
	private static GlobalPos historyKeyFor(AiAssistantEntity assistant) {
		GlobalPos block = assistant.getConfigBlock();
		if (block != null) {
			return block;
		}
		return GlobalPos.of(assistant.level().dimension(), assistant.blockPosition());
	}

	/** 新助手被召唤时打个招呼（方块配置可用则用大模型生成，否则用预设欢迎语）。 */
	private static void greetNewAssistant(ServerPlayer player, AiAssistantEntity assistant) {
		AiBlockConfig config = assistant.getConfig();
		ServerLevel level = (ServerLevel) player.level();
		if (!config.isUsable()) {
			speakAsAssistant(level, assistant,
					Component.translatable("entity.opencraft.ai_assistant.greeting_canned").getString());
			return;
		}
		List<LlmClient.Message> messages = List.of(
				LlmClient.Message.system(config.effectiveSystemPrompt()),
				LlmClient.Message.user("（你刚刚被玩家召唤出来。请用一两句话热情地打个招呼，简单介绍自己，"
						+ "并告诉玩家可以用 /opencraft ask 和你聊天；不要使用动作标记。）"));
		LlmClient.Request request = new LlmClient.Request(
				config.baseUrl,
				config.apiKey,
				config.model,
				Math.min(0.9, config.temperature),
				messages,
				config.timeoutSeconds);
		// 流式打招呼：增量显示在召唤者的 action bar，结束后以助手名义广播完整问候
		// （historyKey 传 null：问候语不写入对话记忆）
		streamReply(player, assistant, request, null);
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

	private static void dismissAssistant(AiAssistantEntity assistant) {
		GlobalPos configBlock = assistant.getConfigBlock();
		assistant.discard();
		// AiAssistantEntity.remove 会兜底熄灭绑定方块（若没有其他助手仍绑定它）
		if (configBlock != null) {
			RECENT_SUMMONS.remove(configBlock);
		}
	}

	/** 清空某个方块（即该方块绑定的助手）的对话历史。 */
	public static void resetHistory(GlobalPos block) {
		if (block != null) {
			HISTORY.remove(block);
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
	// 内部实现
	// ------------------------------------------------------------------

	/**
	 * 发起一次流式对话并处理回调：
	 * - 增量文本按 {@link #FLUSH_INTERVAL_MS} 节流后实时显示在提问玩家的
	 *   action bar（“流式”效果：回复一个字一个字地出现）；
	 * - 流结束后回到服务端线程收尾（{@link #finishStreamReply}）：执行动作标记、
	 *   写入对话历史（historyKey 非空时）、以助手名义把完整回复广播到聊天；
	 * - 失败时向提问玩家发送错误提示。
	 *
	 * 端点不支持流式（忽略 stream 参数）时，LlmClient 会退化为一次性收到
	 * 完整回复（一次 onDelta + onDone），本方法无需区分两种模式。
	 * 所有 HTTP/流解析都在工作线程进行，绝不阻塞服务端主线程。
	 */
	private static void streamReply(ServerPlayer player, AiAssistantEntity assistant,
	                                LlmClient.Request request, GlobalPos historyKey) {
		MinecraftServer server = player.level().getServer();
		StringBuilder buffer = new StringBuilder();
		// 仅在 LlmClient 的工作线程上读写（回调串行），无需加锁
		long[] lastFlushAt = {0L};

		LlmClient.StreamListener listener = new LlmClient.StreamListener() {
			@Override
			public void onDelta(String delta) {
				if (delta == null || delta.isEmpty()) {
					return;
				}
				buffer.append(delta);
				String snapshot = buffer.toString();
				long now = System.currentTimeMillis();
				if (now - lastFlushAt[0] >= FLUSH_INTERVAL_MS) {
					lastFlushAt[0] = now;
					runOnServer(server, () -> showStreamingText(player, snapshot));
				}
			}

			@Override
			public void onDone() {
				String full = buffer.toString();
				runOnServer(server, () -> finishStreamReply(player, assistant, historyKey, full));
			}

			@Override
			public void onError(String error) {
				String reason = error == null || error.isBlank() ? "未知错误" : error;
				runOnServer(server, () -> player.sendSystemMessage(
						Component.translatable("command.opencraft.ask.error", reason)));
			}
		};
		CompletableFuture.runAsync(() -> LlmClient.stream(request, listener), EXECUTOR);
	}

	/** 把流式回复的当前进度显示到玩家的 action bar（overlay，可被下一次更新覆盖）。 */
	private static void showStreamingText(ServerPlayer player, String text) {
		// action bar 是单行渲染：把换行/连续空白折叠成单个空格，避免排版错乱
		String preview = text.replaceAll("\\s+", " ").trim();
		player.displayClientMessage(Component.literal(preview), true);
	}

	/**
	 * 流式结束后（服务端线程）收尾：
	 * 1) 清掉 action bar 上的流式残留；
	 * 2) 解析并执行动作标记（可选，由方块配置控制）；
	 * 3) 把完整回复（含动作标记原文）写入该助手的历史（historyKey 非空时）；
	 * 4) 以助手名义把去掉动作标记的文本广播到聊天。
	 */
	private static void finishStreamReply(ServerPlayer player, AiAssistantEntity assistant,
	                                      GlobalPos historyKey, String full) {
		player.displayClientMessage(Component.empty(), true);
		if (full == null || full.isBlank()) {
			return; // 空回复：不广播也不报错（与旧行为一致）
		}
		String content = full;
		if (assistant.getConfig().allowActions) {
			List<AiAction> actions = AiActionParser.parse(full);
			if (!actions.isEmpty()) {
				executeActions(player, assistant, actions);
			}
			content = AiActionParser.stripActions(full);
		}
		if (historyKey != null) {
			HISTORY.computeIfAbsent(historyKey, k -> new ArrayList<>())
					.add(LlmClient.Message.assistant(full));
		}
		if (!content.isBlank()) {
			speakAsAssistant((ServerLevel) player.level(), assistant, content);
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

	/** 执行助手回复中的动作标记。 */
	private static void executeActions(ServerPlayer player, AiAssistantEntity assistant, List<AiAction> actions) {
		for (AiAction action : actions) {
			OpenCraftMod.LOGGER.info("[OpenCraft] 助手为 {} 执行动作: {}",
					player.getName().getString(), action.describe());
			try {
				switch (action.type()) {
					case GIVE -> giveItem(player, action.itemId(), action.amount());
					case TIME -> setTime(player, action.itemId());
					case HEAL -> player.heal(20.0F);
					case FEED -> player.getFoodData().eat(20, 10.0F);
					case XP -> player.giveExperienceLevels(action.amount());
					case MODE -> {
						boolean following = "follow".equalsIgnoreCase(action.mode());
						assistant.setFollowing(following);
						player.sendSystemMessage(Component.translatable(following
								? "entity.opencraft.ai_assistant.following"
								: "entity.opencraft.ai_assistant.staying"));
					}
					case TELEPORT -> teleportAssistantToPlayer(player, assistant);
					case WEATHER -> setWeather(player, action.weather());
					default -> { /* 忽略未知动作 */ }
				}
			} catch (Exception e) {
				OpenCraftMod.LOGGER.warn("[OpenCraft] 执行动作失败 {}: {}", action.describe(), e.toString());
				player.sendSystemMessage(Component.translatable("command.opencraft.action.failed",
						action.describe()));
			}
		}
	}

	/** 给玩家物品；物品不存在时给出提示。 */
	private static void giveItem(ServerPlayer player, String itemId, int amount) {
		Identifier id;
		try {
			id = Identifier.parse(itemId);
		} catch (Exception e) {
			player.sendSystemMessage(Component.translatable("command.opencraft.action.bad_item", itemId));
			return;
		}
		var holderOpt = BuiltInRegistries.ITEM.get(id);
		if (holderOpt.isEmpty()) {
			player.sendSystemMessage(Component.translatable("command.opencraft.action.bad_item", itemId));
			return;
		}
		Holder<net.minecraft.world.item.Item> holder = holderOpt.get();
		ItemStack stack = new ItemStack(holder, amount);
		boolean added = player.getInventory().add(stack);
		if (added) {
			player.sendSystemMessage(Component.translatable("command.opencraft.action.give.ok",
					amount, stack.getHoverName().getString()));
		} else {
			// 背包满了：掉落到玩家脚边
			player.drop(stack, false);
			player.sendSystemMessage(Component.translatable("command.opencraft.action.give.dropped",
					amount, stack.getHoverName().getString()));
		}
	}

	/** 设置游戏时间。 */
	private static void setTime(ServerPlayer player, String mode) {
		long time;
		switch (mode.toLowerCase(Locale.ROOT)) {
			case "day" -> time = 1000;
			case "noon" -> time = 6000;
			case "sunset" -> time = 12000;
			case "night" -> time = 13000;
			case "midnight" -> time = 18000;
			default -> {
				player.sendSystemMessage(Component.translatable("command.opencraft.action.bad_time", mode));
				return;
			}
		}
		ServerLevel level = (ServerLevel) player.level();
		// 先把天数归零再设置当日时刻（保持当前天数）
		long dayTime = level.getDayTime();
		level.setDayTime((dayTime / 24000) * 24000 + time);
		player.sendSystemMessage(Component.translatable("command.opencraft.action.time.ok", mode));
	}

	/** 设置天气。 */
	private static void setWeather(ServerPlayer player, String weather) {
		ServerLevel level = (ServerLevel) player.level();
		switch (weather.toLowerCase(Locale.ROOT)) {
			case "clear" -> level.setWeatherParameters(6000, 0, false, false);
			case "rain" -> level.setWeatherParameters(0, 6000, true, false);
			case "thunder" -> level.setWeatherParameters(0, 6000, true, true);
			default -> {
				player.sendSystemMessage(Component.translatable("command.opencraft.action.bad_weather", weather));
				return;
			}
		}
		player.sendSystemMessage(Component.translatable("command.opencraft.action.weather.ok", weather));
	}

	/** 让助手瞬移到玩家身边（支持跨维度）。 */
	public static void teleportAssistantToPlayer(ServerPlayer player, AiAssistantEntity assistant) {
		ServerLevel target = (ServerLevel) player.level();
		Vec3 safe = findSafeSpawnPos(target,
				new Vec3(player.getX() + 1.5, player.getY(), player.getZ() + 1.5));
		assistant.teleportTo(target, safe.x, safe.y, safe.z,
				Set.of(), player.getYRot(), player.getXRot(), true);
		target.playSound(null, assistant.blockPosition(), SoundEvents.ALLAY_AMBIENT_WITH_ITEM,
				SoundSource.AMBIENT, 0.5F, 1.2F);
		player.sendSystemMessage(Component.translatable("command.opencraft.action.tp.ok"));
	}

	/** 让助手“开口说话”：播放提示音 + 以 [助手名] 前缀广播到聊天。 */
	public static void speakAsAssistant(ServerLevel level, AiAssistantEntity assistant, String text) {
		Component name = assistant.getDisplayName();
		Component message = Component.literal("[").append(name).append("] ")
				.append(Component.literal(text));
		level.getServer().getPlayerList().broadcastSystemMessage(message, false);
		if (assistant.isAlive()) {
			level.playSound(null, assistant.blockPosition(), SoundEvents.ALLAY_AMBIENT_WITH_ITEM,
					SoundSource.AMBIENT, 0.5F, 1.3F);
		}
	}

	/** 构造“玩家当前游戏状态”上下文，帮助模型给出贴合游戏的回答。 */
	private static String buildGameContext(ServerPlayer player) {
		try {
			net.minecraft.world.level.Level level = player.level();
			long dayTime = level.getDayTime();
			long day = dayTime / 24000 + 1;
			long timeOfDay = dayTime % 24000;
			String phase = timeOfDay < 13000 ? "白天" : "夜晚";
			BlockPos pos = player.blockPosition();
			ItemStack mainHand = player.getMainHandItem();
			String itemName = mainHand.isEmpty() ? "空手" : mainHand.getHoverName().getString();

			return String.format("""
					【玩家当前游戏状态】
					玩家名: %s
					维度: %s
					坐标: x=%d, y=%d, z=%d
					时间: 第 %d 天（%s）
					生命值: %.0f/20
					饥饿值: %d/20
					经验等级: %d
					游戏模式: %s
					主手物品: %s
					""",
					player.getName().getString(),
					level.dimension().identifier(),
					pos.getX(), pos.getY(), pos.getZ(),
					day, phase,
					player.getHealth(),
					player.getFoodData().getFoodLevel(),
					player.experienceLevel,
					player.gameMode.getGameModeForPlayer().name(),
					itemName);
		} catch (Exception e) {
			return "【玩家当前游戏状态】无法获取";
		}
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
}
