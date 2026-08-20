package com.swaydy.opencraft.assistant.player;

import com.mojang.authlib.GameProfile;
import com.swaydy.opencraft.OpenCraftMod;
import com.swaydy.opencraft.ai.AiBlockConfig;
import com.swaydy.opencraft.ai.AiCompanionService;
import com.swaydy.opencraft.ai.AiConfigHandler;
import com.swaydy.opencraft.block.AiLogoBlockEntity;
import com.swaydy.opencraft.block.ModBlocks;
import com.swaydy.opencraft.entity.AiAssistantEntity;
import com.swaydy.opencraft.entity.ModEntities;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家形态助手（假玩家）的注册表与生命周期：召唤/送走/查找/每 tick 安全状态/安全网。
 *
 * 与实体版（ModEntities + AiCompanionService）并行：每个 AI 徽标方块至多绑定一个助手
 * （跨形态统一判定，见 {@link com.swaydy.opencraft.assistant.AssistantFacade}）。
 * **不自动跟随主人**：召唤后停留在原地，只受显式指令（player_goto 等）驱动；
 * 配置方块存在时助手留在世界；方块被拆 → 送走并清空该方块记忆。
 */
public final class PlayerAssistantService {
	/**
	 * 玩家形态助手**进服的系统玩家名**（GameProfile name）：加入消息 / Tab 列表 / /list
	 * 里显示的这个固定名字。与方块配置的显示名（聊天里的「小智 (x,y,z)」）解耦——
	 * 改此常量即可整体改名 bot，无需逐方块改配置。
	 */
	public static final String SYSTEM_NAME = "IAISwayDy";

	/** 按绑定方块键控的活动假玩家。 */
	private static final Map<GlobalPos, AiAssistantPlayer> ACTIVE = new ConcurrentHashMap<>();
	/** 按 UUID 反查绑定方块（供幂等/清理）。 */
	private static final Map<UUID, GlobalPos> BY_UUID = new ConcurrentHashMap<>();

	private PlayerAssistantService() {
	}

	/** 在模组初始化时注册服务器生命周期回调。 */
	public static void init() {
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			ACTIVE.clear();
			BY_UUID.clear();
		});
	}

	/** 全部活动的玩家形态助手。 */
	public static List<AiAssistantPlayer> allActive() {
		return new ArrayList<>(ACTIVE.values());
	}

	/** 绑定到指定 AI 徽标方块的玩家形态助手（一方块至多一个；没有返回 null）。 */
	public static AiAssistantPlayer findBoundTo(GlobalPos block) {
		if (block == null) {
			return null;
		}
		AiAssistantPlayer p = ACTIVE.get(block);
		return p != null && !p.isRemoved() ? p : null;
	}

	/** 某玩家拥有的全部玩家形态助手（跨维度）。 */
	public static List<AiAssistantPlayer> findAssistantsFor(ServerPlayer owner) {
		List<AiAssistantPlayer> result = new ArrayList<>();
		UUID uuid = owner.getUUID();
		for (AiAssistantPlayer p : ACTIVE.values()) {
			if (!p.isRemoved() && uuid.equals(p.getOwnerUuid())) {
				result.add(p);
			}
		}
		return result;
	}

	/** 玩家“最近”的玩家形态助手（按绑定方块距离，同维度优先）；没有返回 null。 */
	public static AiAssistantPlayer findNearestFor(ServerPlayer owner) {
		List<AiAssistantPlayer> owned = findAssistantsFor(owner);
		if (owned.isEmpty()) {
			return null;
		}
		BlockPos playerPos = owner.blockPosition();
		owned.sort((a, b) -> Integer.compare(distanceToPlayer(a, playerPos, owner),
				distanceToPlayer(b, playerPos, owner)));
		return owned.get(0);
	}

	private static int distanceToPlayer(AiAssistantPlayer a, BlockPos playerPos, ServerPlayer owner) {
		GlobalPos block = a.getConfigBlock();
		if (block == null) {
			return Integer.MAX_VALUE;
		}
		if (!block.dimension().equals(owner.level().dimension())) {
			return Integer.MAX_VALUE - 1;
		}
		return Math.abs(block.pos().getX() - playerPos.getX())
				+ Math.abs(block.pos().getY() - playerPos.getY())
				+ Math.abs(block.pos().getZ() - playerPos.getZ());
	}

	/** 按实体 ID 解析“属于该玩家的”玩家形态助手（跨维度）；否则返回 null。 */
	public static AiAssistantPlayer resolveOwned(ServerPlayer owner, int entityId) {
		for (ServerLevel level : owner.level().getServer().getAllLevels()) {
			Entity e = level.getEntity(entityId);
			if (e instanceof AiAssistantPlayer p && owner.getUUID().equals(p.getOwnerUuid())) {
				return p;
			}
		}
		return null;
	}

	/**
	 * 用指定 AI 徽标方块召唤（并绑定）玩家形态助手；方块已被任何形态助手绑定/占用时返回 null。
	 *
	 * 流程：确定性 UUID（按方块）→ 建 ServerPlayer → 载入旧存档（如有）→ 摆安全出生点 →
	 * {@code PlayerList.placeNewPlayer(黑洞连接, player, CommonListenerCookie)} 正式进服 →
	 * 设为生存模式 + 无敌。创建在服务端线程调用。
	 */
	public static AiAssistantPlayer summonFor(ServerPlayer owner, GlobalPos block) {
		if (block == null || owner == null) {
			return null;
		}
		MinecraftServer server = owner.level().getServer();
		ServerLevel blockLevel = server.getLevel(block.dimension());
		if (blockLevel == null || !blockLevel.getBlockState(block.pos()).is(ModBlocks.AI_LOGO_BLOCK)) {
			OpenCraftMod.LOGGER.info("[OpenCraft] 拒绝召唤玩家形态助手：AI 徽标方块不存在或已被移除");
			com.swaydy.opencraft.debug.DebugLog.log("summon",
					"拒绝召唤玩家形态助手：AI 徽标方块不存在或已被移除（{}）",
					block.pos().toShortString());
			return null;
		}
		// 一方块一助手（跨形态）：已被实体形态助手绑定 → 拒绝
		AiAssistantEntity entityBound = ModEntities.findAssistantBoundTo(blockLevel, block);
		if (entityBound != null) {
			if (owner.getUUID().equals(entityBound.getOwnerUuid())) {
				com.swaydy.opencraft.debug.DebugLog.log("summon",
						"拒绝召唤：{} 仍绑定实体形态助手（先送走旧形态才能切换）",
						block.pos().toShortString());
				return null; // 已是实体形态（需先送走实体形态才能切换）
			}
			com.swaydy.opencraft.debug.DebugLog.log("summon",
					"拒绝召唤：{} 已被其他玩家的实体形态助手绑定", block.pos().toShortString());
			return null; // 他人占用
		}
		// 幂等：已召唤自己的玩家形态助手 → 直接返回
		AiAssistantPlayer existing = findBoundTo(block);
		if (existing != null) {
			return owner.getUUID().equals(existing.getOwnerUuid()) ? existing : null;
		}
		AiLogoBlockEntity blockEntity =
				(AiLogoBlockEntity) blockLevel.getBlockEntity(block.pos());
		AiBlockConfig cfg = blockEntity == null ? new AiBlockConfig() : blockEntity.getConfig();
		// 进服系统名固定为 SYSTEM_NAME（GameProfile name，与方块配置显示名解耦）
		GameProfile profile = new GameProfile(assistantUuidFor(block), SYSTEM_NAME);

		// 旧存档重入：PlayerList 里可能还留着上次进服的同一 bot
		AiAssistantPlayer inList = findInPlayerList(server, profile.id());
		if (inList != null) {
			if (owner.getUUID().equals(inList.getOwnerUuid())) {
				com.swaydy.opencraft.debug.DebugLog.log("summon",
						"重新进服：玩家形态助手（bot 名 {}）已在 PlayerList，直接复用（绑定方块 {}）",
						profile.name(), block.pos().toShortString());
				ACTIVE.put(block, inList);
				BY_UUID.put(inList.getUUID(), block);
				return inList;
			}
			com.swaydy.opencraft.debug.DebugLog.log("summon",
					"拒绝召唤：PlayerList 中已有他人持有的 bot（{}）", profile.name());
			return null;
		}

		ServerLevel ownerLevel = (ServerLevel) owner.level();
		AiAssistantPlayer player = new AiAssistantPlayer(server, ownerLevel, profile);
		player.setOwner(owner);
		player.setConfigBlock(block);

		// 载入旧存档（背包/装备/经验/绑定信息，best-effort）
		try {
			PlayerList playerList = server.getPlayerList();
			java.util.Optional<net.minecraft.nbt.CompoundTag> saved =
					playerList.loadPlayerData(player.nameAndId());
			if (saved.isPresent()) {
				player.load(TagValueInput.create(
						net.minecraft.util.ProblemReporter.DISCARDING,
						ownerLevel.registryAccess(), saved.get()));
				com.swaydy.opencraft.debug.DebugLog.log("summon",
						"玩家形态助手（{}）载入旧存档成功（含背包/装备）", profile.name());
			} else {
				com.swaydy.opencraft.debug.DebugLog.log("summon",
						"玩家形态助手（{}）无旧存档，按新助手处理", profile.name());
			}
		} catch (Exception e) {
			OpenCraftMod.LOGGER.debug("[OpenCraft] 载入玩家形态助手存档失败（按新助手处理）: {}", e.toString());
			com.swaydy.opencraft.debug.DebugLog.log("summon",
					"玩家形态助手（{}）载入旧存档失败，按新助手处理: {}",
					profile.name(), e.toString());
		}

		// 摆一个安全出生点（主人旁边向上扫描），再正式进服
		Vec3 spawn = AiCompanionService.findSafeSpawnPos(ownerLevel,
				new Vec3(owner.getX() + 1.5, owner.getY(), owner.getZ() + 1.5));
		player.setPos(spawn.x, spawn.y, spawn.z);

		PlayerList playerList = server.getPlayerList();
		playerList.placeNewPlayer(new FakeConnection(), player,
				CommonListenerCookie.createInitial(profile, false));
		// 生存模式 + 无敌 + 食物满：拥有普通玩家的全部能力，但作为陪玩助手不会轻易死
		player.setGameMode(GameType.SURVIVAL);
		player.getAbilities().invulnerable = true;

		ACTIVE.put(block, player);
		BY_UUID.put(player.getUUID(), block);
		AiConfigHandler.syncBoundBlockPoweredState(blockLevel, block);
		OpenCraftMod.LOGGER.info("[OpenCraft] 玩家 {} 以玩家形态召唤了助手（绑定方块 {}, bot 名 {}）",
				owner.getName().getString(), block.pos().toShortString(), profile.name());
		com.swaydy.opencraft.debug.DebugLog.log("summon",
				"玩家 {} 召唤了玩家形态助手（bot 名 {}，绑定方块 {}，出生点 ({},{},{})）",
				owner.getName().getString(), profile.name(), block.pos().toShortString(),
				(int) spawn.x, (int) spawn.y, (int) spawn.z);
		greet(player, blockLevel, cfg);
		return player;
	}

	/** 玩家形态助手的确定性 UUID：由绑定方块唯一决定（重进/重启后仍是同一个 bot）。 */
	public static UUID assistantUuidFor(GlobalPos block) {
		return UUID.nameUUIDFromBytes(("opencraft:assistant:" + block.dimension().identifier()
				+ ":" + block.pos().toShortString()).getBytes(StandardCharsets.UTF_8));
	}

	/** 在 PlayerList 里找指定 UUID 的玩家（可能是上次进服遗留的 bot）。 */
	private static AiAssistantPlayer findInPlayerList(MinecraftServer server, UUID uuid) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p instanceof AiAssistantPlayer assistant
					&& p.getUUID().equals(uuid) && !p.isRemoved()) {
				return assistant;
			}
		}
		return null;
	}

	/** 送走绑定到指定方块的玩家形态助手（PlayerList.remove 会保存存档 + 移出世界 + 广播下线）。 */
	public static boolean dismiss(GlobalPos block) {
		if (block == null) {
			return false;
		}
		AiAssistantPlayer p = ACTIVE.remove(block);
		if (p == null || p.isRemoved()) {
			return false;
		}
		BY_UUID.remove(p.getUUID());
		com.swaydy.opencraft.debug.DebugLog.log("summon",
				"送走玩家形态助手（bot 名 {}，绑定方块 {}）",
				p.getName().getString(), block.pos().toShortString());
		try {
			MinecraftServer server = p.level().getServer();
			if (server != null) {
				server.getPlayerList().remove(p);
			}
		} catch (Exception e) {
			OpenCraftMod.LOGGER.warn("[OpenCraft] 移除玩家形态助手异常: {}", e.toString());
		}
		if (p.level() instanceof ServerLevel sl) {
			AiConfigHandler.syncBoundBlockPoweredState(sl, block);
		}
		return true;
	}

	/** 送走某玩家的全部玩家形态助手；没有任何可送走时返回 false。 */
	public static boolean dismissAllFor(ServerPlayer owner) {
		List<AiAssistantPlayer> owned = findAssistantsFor(owner);
		boolean any = false;
		for (AiAssistantPlayer p : owned) {
			GlobalPos block = p.getConfigBlock();
			if (block != null) {
				any |= dismiss(block);
			}
		}
		return any;
	}

	/** 某方块是否绑定了玩家形态助手。 */
	public static boolean isBlockBound(GlobalPos block) {
		return findBoundTo(block) != null;
	}

	// ------------------------------------------------------------------
	// 每 tick / 慢 tick（由 AiAssistantPlayer.tick 调用，服务端线程）
	// ------------------------------------------------------------------

	/**
	 * 每 tick：维持安全状态——无敌 + 食物补满（拥有普通玩家的全部能力，
	 * 但作为陪玩助手不会轻易死）。**不再有跟随逻辑**：助手召唤后停留在原地。
	 */
	static void keepSafeState(AiAssistantPlayer player) {
		if (!player.getAbilities().invulnerable) {
			player.getAbilities().invulnerable = true;
		}
		if (player.getFoodData().getFoodLevel() < 20) {
			player.getFoodData().setFoodLevel(20);
		}
	}

	/**
	 * 每 40 tick：安全网——绑定方块校验（方块被拆 → 送走并清空该方块记忆）。
	 */
	static void onSlowTick(AiAssistantPlayer player) {
		if (player == null || player.isRemoved()) {
			return;
		}
		GlobalPos block = player.getConfigBlock();
		if (block == null || player.isBoundBlockGone()) {
			// 无绑定 / 绑定方块已消失 → 送走并清空该方块记忆（与实体版安全网一致）
			GlobalPos gone = block;
			com.swaydy.opencraft.debug.DebugLog.log("summon",
					"安全网：玩家形态助手绑定方块{}已消失，送走并清空记忆",
					gone == null ? "（无绑定）" : gone.pos().toShortString());
			if (gone != null) {
				dismiss(gone);
				AiCompanionService.resetHistory(gone);
			}
		}
	}

	/** 新助手进服时打一个罐头欢迎语（不调 LLM，避免进服瞬间发起额外请求）。 */
	private static void greet(AiAssistantPlayer player, ServerLevel level, AiBlockConfig cfg) {
		String text = Component.translatable("entity.opencraft.ai_assistant.greeting_canned").getString();
		AiCompanionService.speakAsAssistant(level, player, text);
	}
}
