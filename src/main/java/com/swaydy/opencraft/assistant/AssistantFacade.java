package com.swaydy.opencraft.assistant;

import com.swaydy.opencraft.ai.AiBlockConfig;
import com.swaydy.opencraft.ai.AiCompanionService;
import com.swaydy.opencraft.ai.AiConfigHandler;
import com.swaydy.opencraft.assistant.player.AiAssistantPlayer;
import com.swaydy.opencraft.assistant.player.PlayerAssistantService;
import com.swaydy.opencraft.block.AiLogoBlockEntity;
import com.swaydy.opencraft.entity.AiAssistantEntity;
import com.swaydy.opencraft.entity.ModEntities;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 跨形态的统一入口：把“实体形态（PathfinderMob）与玩家形态（假玩家）”当成同一套
 * 助手对待——一个方块至多一个助手，查找/召唤/送走/对话都按统一的规则路由。
 *
 * <p><b>设计原则（本次重构）</b>：AI 助手本身就是“像多人联机客户端一样加入游戏”的
 * 真玩家——{@link com.swaydy.opencraft.assistant.player.AiAssistantPlayer}，
 * 召唤即经 {@code PlayerList.placeNewPlayer} 正式进服，这一性质与 Agent 预设无关。
 * <b>Agent 预设是另一套东西</b>：它只决定助手的 LLM 行为（人设、可用工具、最大行动轮数），
 * 绝不决定身体形态。实体形态（PathfinderMob）仅作为旧存档遗留保留
 * （查找/送走仍兼容），不再通过任何用户路径召唤。
 */
public final class AssistantFacade {
	private AssistantFacade() {
	}

	/** 读取方块配置；方块不存在/不是 AI 徽标方块时返回 null。 */
	public static AiBlockConfig configOf(ServerLevel anyLevel, GlobalPos block) {
		if (anyLevel == null || block == null) {
			return null;
		}
		ServerLevel level = anyLevel.getServer().getLevel(block.dimension());
		if (level == null) {
			return null;
		}
		if (level.getBlockEntity(block.pos()) instanceof AiLogoBlockEntity be) {
			return be.getConfig();
		}
		return null;
	}

	/** 某玩家的全部助手（实体形态 + 玩家形态，跨维度）。 */
	public static List<AiAssistant> findAssistantsFor(ServerPlayer owner) {
		List<AiAssistant> result = new ArrayList<>();
		result.addAll(ModEntities.findAssistantsFor(owner));
		result.addAll(PlayerAssistantService.findAssistantsFor(owner));
		return result;
	}

	/** 玩家“最近”的助手（按绑定方块距离，实体形态与玩家形态都算）。 */
	public static AiAssistant findNearestFor(ServerPlayer owner) {
		AiAssistantEntity entity = ModEntities.findNearestAssistantFor(owner);
		AiAssistantPlayer player = PlayerAssistantService.findNearestFor(owner);
		if (entity == null) {
			return player;
		}
		if (player == null) {
			return entity;
		}
		GlobalPos eb = entity.getConfigBlock();
		GlobalPos pb = player.getConfigBlock();
		int ed = eb == null ? Integer.MAX_VALUE : blockDist(owner, eb);
		int pd = pb == null ? Integer.MAX_VALUE : blockDist(owner, pb);
		return ed <= pd ? entity : player;
	}

	private static int blockDist(ServerPlayer owner, GlobalPos block) {
		if (!block.dimension().equals(owner.level().dimension())) {
			return Integer.MAX_VALUE - 1;
		}
		var pos = owner.blockPosition();
		return Math.abs(block.pos().getX() - pos.getX())
				+ Math.abs(block.pos().getY() - pos.getY())
				+ Math.abs(block.pos().getZ() - pos.getZ());
	}

	/** 绑定到指定方块的助手（任何形态）；没有返回 null。 */
	public static AiAssistant findBoundTo(ServerLevel anyLevel, GlobalPos block) {
		AiAssistantEntity entity = ModEntities.findAssistantBoundTo(anyLevel, block);
		if (entity != null) {
			return entity;
		}
		return PlayerAssistantService.findBoundTo(block);
	}

	/** 指定方块是否已绑定任意形态的助手。 */
	public static boolean isConfigBlockBound(ServerLevel anyLevel, GlobalPos block) {
		return findBoundTo(anyLevel, block) != null;
	}

	/**
	 * 召唤绑定到指定方块的助手：**一律召唤玩家形态（真正的 ServerPlayer bot，像客户端一样进服）**，
	 * 与方块配置的 Agent 预设无关（预设只决定 LLM 行为）。
	 *
	 * 旧存档兼容：目标方块若仍绑定着【本人】的实体形态遗留助手，先送走它再召唤玩家 bot
	 * （一方块一助手的规则不变）；被他人绑定则拒绝返回 null。
	 */
	public static AiAssistant summon(ServerPlayer owner, GlobalPos block) {
		if (block == null || owner == null) {
			return null;
		}
		// 旧存档遗留：方块仍被实体形态助手绑定 → 先送走（本人的）再召唤玩家 bot
		ServerLevel blockLevel = owner.level().getServer().getLevel(block.dimension());
		AiAssistantEntity legacyEntity = blockLevel == null
				? null : ModEntities.findAssistantBoundTo(blockLevel, block);
		if (legacyEntity != null) {
			if (legacyEntity.getOwnerUuid() != null
					&& legacyEntity.getOwnerUuid().equals(owner.getUUID())) {
				AiCompanionService.dismissAssistantEntity(legacyEntity);
			} else {
				// 他人占用：一方块一助手，拒绝
				return null;
			}
		}
		// 玩家形态：真正的 ServerPlayer bot，像多人联机客户端一样经 PlayerList 进服
		return PlayerAssistantService.summonFor(owner, block);
	}

	/** 自动召唤：找最近的未绑定方块并按玩家形态召唤；找不到返回 null。 */
	public static AiAssistant summonNearest(ServerPlayer owner) {
		GlobalPos block = AiConfigHandler.findNearestConfigBlock(
				(ServerLevel) owner.level(), owner.blockPosition(), 48, true);
		if (block == null) {
			return null;
		}
		return summon(owner, block);
	}

	/** 送走指定的助手（按形态路由；重复调用幂等返回 false）。 */
	public static boolean dismiss(AiAssistant assistant) {
		if (assistant instanceof AiAssistantEntity entity) {
			return AiCompanionService.dismissAssistantEntity(entity);
		}
		if (assistant instanceof AiAssistantPlayer player) {
			GlobalPos block = player.getConfigBlock();
			return block != null && PlayerAssistantService.dismiss(block);
		}
		return false;
	}

	/** 送走某玩家的全部助手（两种形态）；没有任何可送走时返回 false。 */
	public static boolean dismissAllFor(ServerPlayer owner) {
		boolean any = AiCompanionService.dismissAllFor(owner);
		any |= PlayerAssistantService.dismissAllFor(owner);
		return any;
	}

	/** 送走玩家“最近”的助手（两种形态都算）；没有可送走时返回 false。 */
	public static boolean dismissFor(ServerPlayer owner) {
		AiAssistant nearest = findNearestFor(owner);
		return nearest != null && dismiss(nearest);
	}

	/** 按实体 ID 解析“属于该玩家的”助手（任何形态，跨维度）。 */
	public static AiAssistant resolveOwned(ServerPlayer owner, int entityId) {
		AiAssistantEntity entity = AiCompanionService.resolveOwnedAssistant(owner, entityId);
		if (entity != null) {
			return entity;
		}
		return PlayerAssistantService.resolveOwned(owner, entityId);
	}

	/** 按选择器（名字/显示名/名字(坐标)）匹配某玩家的助手（两种形态都算）。 */
	public static List<AiAssistant> findAssistantsBySelector(ServerPlayer owner, String selector) {
		List<AiAssistant> result = new ArrayList<>();
		for (AiAssistantEntity e : ModEntities.findAssistantsBySelector(owner, selector)) {
			result.add(e);
		}
		if (selector == null) {
			return result;
		}
		String s = selector.trim().toLowerCase(Locale.ROOT);
		if (s.isEmpty()) {
			return result;
		}
		for (AiAssistantPlayer p : PlayerAssistantService.findAssistantsFor(owner)) {
			AiBlockConfig cfg = p.getConfig();
			String name = cfg == null ? "" : cfg.effectiveName();
			GlobalPos block = p.getConfigBlock();
			if (s.equals(name.toLowerCase(Locale.ROOT))) {
				result.add(p);
				continue;
			}
			if (block == null) {
				continue;
			}
			String xyz = block.pos().getX() + "," + block.pos().getY() + "," + block.pos().getZ();
			String display = name + " (" + xyz + ")";
			String compact = name + "(" + xyz + ")";
			String atForm = name + "@" + xyz;
			if (s.equals(display.toLowerCase(Locale.ROOT))
					|| s.equals(compact.toLowerCase(Locale.ROOT))
					|| s.equals(atForm.toLowerCase(Locale.ROOT))) {
				result.add(p);
			}
		}
		return result;
	}
}