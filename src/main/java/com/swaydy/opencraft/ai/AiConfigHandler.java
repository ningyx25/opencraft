package com.swaydy.opencraft.ai;

import com.swaydy.opencraft.OpenCraftMod;
import com.swaydy.opencraft.assistant.AiAssistant;
import com.swaydy.opencraft.assistant.AssistantFacade;
import com.swaydy.opencraft.block.AiLogoBlockEntity;
import com.swaydy.opencraft.net.AiConfigPayloads;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * AI 配置编辑器（AI 徽标方块即配置载体）的服务器端处理：
 * - openFor：读取目标方块保存的配置发给客户端（打开编辑器）；
 * - save：把编辑后的配置写回目标方块（即时生效，随方块存档持久化）；
 * - summonWithBlock / dismissWithBlock：配置界面“召唤/不召唤助手”合并按钮的两半——
 *   没有助手绑定时点按钮 = 召唤并绑定助手；已绑定自己的助手时点按钮 = 送走（取消召唤）。
 *
 * 配置完全保存在游戏内方块实体中，不依赖任何外部配置文件。
 * 只有管理员（op）可以保存；API Key 的任何部分都不会发送给客户端。
 */
public final class AiConfigHandler {
	private AiConfigHandler() {
	}

	/** 右键方块：发送该方块的配置数据，让客户端打开配置编辑器。 */
	public static void openFor(ServerPlayer player, BlockPos pos, ResourceKey<Level> dimension) {
		AiLogoBlockEntity blockEntity = findBlock(player, pos, dimension);
		if (blockEntity == null) {
			player.sendSystemMessage(Component.translatable("command.opencraft.config.no_block"));
			return;
		}
		sendData(player, blockEntity.getConfig().toData(), canEdit(player), pos, dimension);
	}

	/** 处理客户端保存请求：写回目标方块。 */
	public static void save(ServerPlayer player, BlockPos pos, ResourceKey<Level> dimension, String json) {
		if (!canEdit(player)) {
			player.sendSystemMessage(Component.translatable("command.opencraft.config.no_permission"));
			sendData(player, buildData(player, pos, dimension), false, pos, dimension);
			return;
		}
		AiLogoBlockEntity blockEntity = findBlock(player, pos, dimension);
		if (blockEntity == null) {
			player.sendSystemMessage(Component.translatable("command.opencraft.config.no_block"));
			return;
		}
		AiConfigData data;
		try {
			data = AiConfigData.fromJson(json);
		} catch (Exception e) {
			player.sendSystemMessage(Component.translatable("command.opencraft.config.invalid"));
			return;
		}
		if (data == null) {
			player.sendSystemMessage(Component.translatable("command.opencraft.config.invalid"));
			return;
		}
		blockEntity.applyData(data);
		// 循环事件开关变化即时生效：该方块已绑定助手时，同步启动/停止对应的循环实例
		ServerLevel saveLevel = player.level().getServer().getLevel(dimension);
		GlobalPos saveBindPos = GlobalPos.of(dimension, pos);
		if (saveLevel != null && AssistantFacade.isConfigBlockBound(saveLevel, saveBindPos)) {
			com.swaydy.opencraft.assistant.player.PlayerAssistantService.syncLoopsForBlock(
					saveLevel, saveBindPos);
			// 皮肤选择变化即时生效：重新广播该方块的助手皮肤 id（客户端替换贴图）
			com.swaydy.opencraft.assistant.skin.AssistantSkinSync.syncForBlock(saveLevel, saveBindPos);
		}
		OpenCraftMod.LOGGER.info("[OpenCraft] 玩家 {} 更新了方块({})的 AI 配置",
				player.getName().getString(), pos.toShortString());
		com.swaydy.opencraft.logging.DebugLog.log("config",
				"玩家 {} 保存了方块 {} 的配置（模型={}，名字={}，agent={}，baseUrl={}）",
				player.getName().getString(), pos.toShortString(),
				blockEntity.getConfig().model, blockEntity.getConfig().effectiveName(),
				blockEntity.getConfig().agent, blockEntity.getConfig().baseUrl);
		player.sendSystemMessage(Component.translatable("command.opencraft.config.saved"));
		sendData(player, blockEntity.getConfig().toData(), true, pos, dimension);
	}

	/** 用指定方块召唤（并绑定）AI 助手，助手将使用该方块的配置。一个方块至多一个助手。 */
	public static void summonWithBlock(ServerPlayer player, BlockPos pos, ResourceKey<Level> dimension) {
		AiLogoBlockEntity blockEntity = findBlock(player, pos, dimension);
		if (blockEntity == null) {
			player.sendSystemMessage(Component.translatable("command.opencraft.config.no_block"));
			return;
		}
		GlobalPos bindPos = GlobalPos.of(dimension, pos);
		// 该方块已绑定助手（任何形态）：是自己的 → 视为成功（幂等）；是别人的 → 拒绝（一方块一助手）
		AiAssistant existing = AssistantFacade.findBoundTo(
				player.level().getServer().getLevel(dimension), bindPos);
		if (existing != null) {
			if (existing.getOwnerUuid() != null && existing.getOwnerUuid().equals(player.getUUID())) {
				player.sendSystemMessage(Component.translatable("command.opencraft.summon.success"));
			} else {
				player.sendSystemMessage(Component.translatable("command.opencraft.summon.block_occupied"));
			}
			// 绑定后顺手把编辑器刷新为该方块配置
			sendData(player, blockEntity.getConfig().toData(), canEdit(player), pos, dimension);
			return;
		}
		AiAssistant assistant = AssistantFacade.summon(player, bindPos);
		if (assistant == null) {
			player.sendSystemMessage(Component.translatable("command.opencraft.summon.failed"));
			com.swaydy.opencraft.logging.DebugLog.log("summon",
					"配置界面召唤失败（方块 {}）", pos.toShortString());
			return;
		}
		com.swaydy.opencraft.logging.DebugLog.log("summon",
				"配置界面：玩家 {} 用方块 {} 召唤了助手", player.getName().getString(),
				pos.toShortString());
		// 新绑定方块亮起（若有其他助手仍绑定旧方块，由实体 remove 兜底熄灭）
		syncBoundBlockPoweredState(player.level().getServer().getLevel(dimension), bindPos);
		player.sendSystemMessage(Component.translatable("command.opencraft.summon.success"));
		// 绑定后顺手把编辑器刷新为该方块配置
		sendData(player, blockEntity.getConfig().toData(), canEdit(player), pos, dimension);
	}

	/**
	 * 送走绑定到指定方块的助手（配置界面合并按钮的“不召唤”状态）。
	 * 只允许助手的主人送走；别人的助手会被拒绝；本来就无人绑定时视为已是“未召唤”状态。
	 */
	public static void dismissWithBlock(ServerPlayer player, BlockPos pos, ResourceKey<Level> dimension) {
		AiLogoBlockEntity blockEntity = findBlock(player, pos, dimension);
		if (blockEntity == null) {
			player.sendSystemMessage(Component.translatable("command.opencraft.config.no_block"));
			return;
		}
		ServerLevel level = player.level().getServer().getLevel(dimension);
		GlobalPos bindPos = GlobalPos.of(dimension, pos);
		AiAssistant existing = level == null ? null : AssistantFacade.findBoundTo(level, bindPos);
		if (existing == null) {
			// 本来就没有助手绑定：幂等视为成功，刷新界面即可
			sendData(player, blockEntity.getConfig().toData(), canEdit(player), pos, dimension);
			return;
		}
		if (existing.getOwnerUuid() == null || !existing.getOwnerUuid().equals(player.getUUID())) {
			player.sendSystemMessage(Component.translatable("command.opencraft.dismiss.block_not_owned"));
			sendData(player, blockEntity.getConfig().toData(), canEdit(player), pos, dimension);
			return;
		}
		if (AssistantFacade.dismiss(existing)) {
			player.sendSystemMessage(Component.translatable("command.opencraft.dismiss.success"));
			com.swaydy.opencraft.logging.DebugLog.log("summon",
					"配置界面：玩家 {} 送走了方块 {} 绑定的助手", player.getName().getString(),
					pos.toShortString());
		}
		// 送走后刷新界面（按钮回到“召唤”状态）
		sendData(player, blockEntity.getConfig().toData(), canEdit(player), pos, dimension);
	}

	/**
	 * 配置界面聊天窗口：向本方块的助手发送一条消息（异步）。
	 * - 本方块已绑定自己的助手 → 直接与它对话；
	 * - 已绑定别人的助手 → 拒绝（一方块一助手）；
	 * - 尚未绑定任何助手 → 自动用本方块召唤并绑定一个，再开始对话（与命令 ask 的便利一致）。
	 * 回复以 S2C 事件回传窗口（不广播世界聊天）。
	 */
	public static void chatWithBlock(ServerPlayer player, BlockPos pos, ResourceKey<Level> dimension,
	                                 String message) {
		String text = message == null ? "" : message.trim();
		if (text.isEmpty()) {
			return;
		}
		AiLogoBlockEntity blockEntity = findBlock(player, pos, dimension);
		if (blockEntity == null) {
			sendChatError(player, pos, dimension, "command.opencraft.config.no_block");
			return;
		}
		ServerLevel level = player.level().getServer().getLevel(dimension);
		GlobalPos bindPos = GlobalPos.of(dimension, pos);
		AiAssistant assistant = level == null ? null : AssistantFacade.findBoundTo(level, bindPos);
		if (assistant == null) {
			// 便利：还没有助手时自动用本方块召唤一个（按形态路由，绑定本方块）
			assistant = AssistantFacade.summon(player, bindPos);
			if (assistant == null) {
				sendChatError(player, pos, dimension, "command.opencraft.summon.failed");
				return;
			}
			syncBoundBlockPoweredState(level, bindPos);
		} else if (assistant.getOwnerUuid() == null || !assistant.getOwnerUuid().equals(player.getUUID())) {
			sendChatError(player, pos, dimension, "command.opencraft.summon.block_occupied");
			return;
		}
		AiCompanionService.askGui(player, assistant, text, pos, dimension);
	}

	/** 配置界面聊天窗口「中断」按钮：中断本方块助手正在进行的任务（服务端线程）。 */
	public static void interruptWithBlock(ServerPlayer player, BlockPos pos, ResourceKey<Level> dimension) {
		AiLogoBlockEntity blockEntity = findBlock(player, pos, dimension);
		if (blockEntity == null) {
			return;
		}
		ServerLevel level = player.level().getServer().getLevel(dimension);
		GlobalPos bindPos = GlobalPos.of(dimension, pos);
		AiAssistant assistant = level == null ? null : AssistantFacade.findBoundTo(level, bindPos);
		if (assistant == null) {
			sendChatError(player, pos, dimension, "command.opencraft.interrupt.none");
			return;
		}
		if (!com.swaydy.opencraft.agent.AgentRuntime.interrupt(bindPos)) {
			sendChatError(player, pos, dimension, "command.opencraft.interrupt.none");
			return;
		}
		// AgentRuntime.interrupt 已向窗口发 "reply"（已中断）事件并清理浮层
	}

	/** 配置界面聊天窗口：把本方块助手的对话历史以 "history" 事件回传（用于打开时填充）。 */
	public static void sendChatHistory(ServerPlayer player, BlockPos pos, ResourceKey<Level> dimension) {
		AiLogoBlockEntity blockEntity = findBlock(player, pos, dimension);
		if (blockEntity == null) {
			return;
		}
		ServerLevel level = player.level().getServer().getLevel(dimension);
		GlobalPos bindPos = GlobalPos.of(dimension, pos);
		AiAssistant assistant = level == null ? null : AssistantFacade.findBoundTo(level, bindPos);
		// 只把自己的助手历史发给本人；没绑定/别人的 → 空历史
		boolean own = assistant != null && assistant.getOwnerUuid() != null
				&& assistant.getOwnerUuid().equals(player.getUUID());
		String json = own ? AiCompanionService.historyJson(bindPos) : "[]";
		try {
			ServerPlayNetworking.send(player,
					new AiConfigPayloads.AiConfigChatEventPayload(
							"history", Component.literal(json), pos, dimension));
		} catch (Exception e) {
			OpenCraftMod.LOGGER.debug("[OpenCraft] 发送聊天历史失败（可能是模拟连接）: {}", e.toString());
		}
	}

	/** 给配置界面聊天窗口回传一个错误事件（Component 用翻译 key 构造，客户端本地化）。 */
	private static void sendChatError(ServerPlayer player, BlockPos pos, ResourceKey<Level> dimension,
	                                  String translationKey) {
		try {
			ServerPlayNetworking.send(player,
					new AiConfigPayloads.AiConfigChatEventPayload(
							"error", Component.translatable(translationKey), pos, dimension));
		} catch (Exception e) {
			OpenCraftMod.LOGGER.debug("[OpenCraft] 发送聊天窗口错误事件失败（可能是模拟连接）: {}", e.toString());
		}
	}

	/**
	 * 让指定 AI 徽标方块的 powered 状态自动反映“是否有助手绑定它”：
	 * 有任一助手绑定 → 亮起（powered=true）；无人绑定 → 熄灭（powered=false）。
	 */
	public static void syncBoundBlockPoweredState(ServerLevel level, GlobalPos blockPos) {
		if (level == null || blockPos == null) {
			return;
		}
		net.minecraft.world.level.block.state.BlockState state = level.getBlockState(blockPos.pos());
		// 方块不存在或已不是 AI 徽标方块（如刚被破坏）时直接返回
		if (!(state.getBlock() instanceof com.swaydy.opencraft.block.AiLogoBlock)) {
			return;
		}
		boolean anyBound = AssistantFacade.isConfigBlockBound(level, blockPos);
		boolean powered = state.getValue(com.swaydy.opencraft.block.AiLogoBlock.POWERED);
		if (powered != anyBound) {
			level.setBlock(blockPos.pos(), state.setValue(
					com.swaydy.opencraft.block.AiLogoBlock.POWERED, anyBound),
					net.minecraft.world.level.block.Block.UPDATE_ALL);
		}
	}

	/** 是否有权限修改配置（op）。 */
	public static boolean canEdit(ServerPlayer player) {
		PlayerList playerList = player.level().getServer().getPlayerList();
		return playerList.isOp(new NameAndId(player.getGameProfile()));
	}

	/** 解析目标方块（跨维度）。 */
	public static AiLogoBlockEntity findBlock(ServerPlayer player, BlockPos pos, ResourceKey<Level> dimension) {
		ServerLevel level = player.level().getServer().getLevel(dimension);
		if (level == null) {
			return null;
		}
		if (level.getBlockEntity(pos) instanceof AiLogoBlockEntity blockEntity) {
			return blockEntity;
		}
		return null;
	}

	/**
	 * 在指定区域里找一个 AI 徽标方块作为配置来源；找不到返回 null。
	 * unboundOnly 为 true 时只找“还没有任何助手绑定”的方块（多助手规则：一方块一助手）。
	 */
	public static GlobalPos findNearestConfigBlock(ServerLevel level, BlockPos center, int radius,
	                                               boolean unboundOnly) {
		AiLogoBlockEntity best = null;
		int bestDist = Integer.MAX_VALUE;
		int chunkRadius = (radius >> 4) + 1;
		int centerChunkX = center.getX() >> 4;
		int centerChunkZ = center.getZ() >> 4;
		for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
			for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
				LevelChunk chunk = level.getChunk(centerChunkX + dx, centerChunkZ + dz);
				for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
					if (blockEntity instanceof AiLogoBlockEntity aiBlockEntity) {
						BlockPos pos = blockEntity.getBlockPos();
						if (unboundOnly
								&& AssistantFacade.isConfigBlockBound(level,
										GlobalPos.of(level.dimension(), pos))) {
							continue;
						}
						int dist = Math.abs(pos.getX() - center.getX())
								+ Math.abs(pos.getY() - center.getY())
								+ Math.abs(pos.getZ() - center.getZ());
						if (dist < bestDist) {
							bestDist = dist;
							best = aiBlockEntity;
						}
					}
				}
			}
		}
		return best == null ? null : GlobalPos.of(level.dimension(), best.getBlockPos());
	}

	private static AiConfigData buildData(ServerPlayer player, BlockPos pos, ResourceKey<Level> dimension) {
		AiLogoBlockEntity blockEntity = findBlock(player, pos, dimension);
		return blockEntity == null ? new AiBlockConfig().toData() : blockEntity.getConfig().toData();
	}

	private static void sendData(ServerPlayer player, AiConfigData data, boolean canEdit,
	                             BlockPos pos, ResourceKey<Level> dimension) {
		// 携带“本方块是否已绑定助手 / 是否绑定的是本玩家”状态，
		// 供配置界面把“召唤/不召唤助手”合并为同一个按钮（未绑定→召唤；已绑定自己的→送走）。
		ServerLevel level = player.level().getServer().getLevel(dimension);
		boolean bound = false;
		boolean boundByMe = false;
		if (level != null) {
			AiAssistant boundAssistant =
					AssistantFacade.findBoundTo(level, GlobalPos.of(dimension, pos));
			bound = boundAssistant != null;
			boundByMe = bound && boundAssistant.getOwnerUuid() != null
					&& boundAssistant.getOwnerUuid().equals(player.getUUID());
		}
		String loopStatusJson = loopStatusJson(level, pos, dimension);
		try {
			ServerPlayNetworking.send(player,
					new AiConfigPayloads.AiConfigDataPayload(
							data.toJson(), canEdit, bound, boundByMe,
							loopStatusJson, pos, dimension));
		} catch (Exception e) {
			OpenCraftMod.LOGGER.debug("[OpenCraft] 发送 AI 配置数据失败（可能是模拟连接）: {}", e.toString());
		}
	}

	/**
	 * 本方块活动循环事件实例的 JSON 快照（[{id, phase, iteration}]）。
	 * 供配置界面第 3 页显示每个循环事件“运行中/已停止、阶段、迭代次数”。
	 * 无实例时返回 "[]"。
	 */
	private static String loopStatusJson(ServerLevel level, BlockPos pos, ResourceKey<Level> dimension) {
		com.google.gson.JsonArray array = new com.google.gson.JsonArray();
		if (level != null) {
			GlobalPos anchor = GlobalPos.of(dimension, pos);
			for (com.swaydy.opencraft.loop.LoopStatus st
					: com.swaydy.opencraft.loop.LoopEngine.status()) {
				if (!anchor.equals(st.anchor())) {
					continue;
				}
				com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
				obj.addProperty("id", st.defId());
				obj.addProperty("phase", st.phase().name());
				obj.addProperty("iteration", st.iteration());
				array.add(obj);
			}
		}
		return array.toString();
	}
}
