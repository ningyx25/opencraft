package com.swaydy.opencraft.assistant;

import com.swaydy.opencraft.ai.AiBlockConfig;
import com.swaydy.opencraft.ai.AiConfigHandler;
import com.swaydy.opencraft.assistant.player.AiAssistantPlayer;
import com.swaydy.opencraft.assistant.player.PlayerAssistantService;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 助手服务的统一入口：召唤/查找/送走/对话路由都以这里为门面。
 *
 * <p><b>设计原则</b>：AI 助手本身就是“像多人联机客户端一样加入游戏”的真玩家——
 * {@link com.swaydy.opencraft.assistant.player.AiAssistantPlayer}，召唤即经
 * {@code PlayerList.placeNewPlayer} 正式进服，这一性质与 Agent 预设无关。
 * <b>Agent 预设是另一套东西</b>：它只决定助手的 LLM 行为（人设、可用工具、最大行动轮数），
 * 绝不决定身体形态。
 */
public final class AssistantFacade {
	private AssistantFacade() {
	}

	/** 某玩家的全部助手（跨维度）。 */
	public static List<AiAssistant> findAssistantsFor(ServerPlayer owner) {
		return new ArrayList<>(PlayerAssistantService.findAssistantsFor(owner));
	}

	/** 玩家“最近”的助手（按绑定方块距离）。 */
	public static AiAssistant findNearestFor(ServerPlayer owner) {
		return PlayerAssistantService.findNearestFor(owner);
	}

	/** 绑定到指定方块的助手；没有返回 null。 */
	public static AiAssistant findBoundTo(ServerLevel anyLevel, GlobalPos block) {
		return PlayerAssistantService.findBoundTo(block);
	}

	/** 指定方块是否已绑定助手。 */
	public static boolean isConfigBlockBound(ServerLevel anyLevel, GlobalPos block) {
		return findBoundTo(anyLevel, block) != null;
	}

	/**
	 * 召唤绑定到指定方块的助手：**真正的 ServerPlayer bot，像客户端一样进服**，
	 * 与方块配置的 Agent 预设无关（预设只决定 LLM 行为）。
	 *
	 * 多助手规则（每个 AI 徽标方块至多绑定一个助手）：
	 * 目标方块已被本人绑定 → 直接返回（幂等）；被他人绑定 → 拒绝返回 null。
	 */
	public static AiAssistantPlayer summon(ServerPlayer owner, GlobalPos block) {
		if (block == null || owner == null) {
			return null;
		}
		return PlayerAssistantService.summonFor(owner, block);
	}

	/** 自动召唤：找最近的未绑定方块并召唤；找不到返回 null。 */
	public static AiAssistant summonNearest(ServerPlayer owner) {
		GlobalPos block = AiConfigHandler.findNearestConfigBlock(
				(ServerLevel) owner.level(), owner.blockPosition(), 48, true);
		if (block == null) {
			return null;
		}
		return summon(owner, block);
	}

	/** 送走指定的助手（重复调用幂等返回 false）。 */
	public static boolean dismiss(AiAssistant assistant) {
		if (assistant instanceof AiAssistantPlayer player) {
			GlobalPos block = player.getConfigBlock();
			return block != null && PlayerAssistantService.dismiss(block);
		}
		return false;
	}

	/** 送走某玩家的全部助手；没有任何可送走时返回 false。 */
	public static boolean dismissAllFor(ServerPlayer owner) {
		return PlayerAssistantService.dismissAllFor(owner);
	}

	/** 送走玩家“最近”的助手；没有可送走时返回 false。 */
	public static boolean dismissFor(ServerPlayer owner) {
		AiAssistant nearest = findNearestFor(owner);
		return nearest != null && dismiss(nearest);
	}

	/** 按实体 ID 解析“属于该玩家的”助手（跨维度）。 */
	public static AiAssistant resolveOwned(ServerPlayer owner, int entityId) {
		return PlayerAssistantService.resolveOwned(owner, entityId);
	}

	/** 按选择器（名字/显示名/名字(坐标)）匹配某玩家的助手。 */
	public static List<AiAssistant> findAssistantsBySelector(ServerPlayer owner, String selector) {
		List<AiAssistant> result = new ArrayList<>();
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
