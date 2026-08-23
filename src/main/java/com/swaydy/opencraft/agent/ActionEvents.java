package com.swaydy.opencraft.agent;

import java.util.Map;
import java.util.TreeMap;

/**
 * 异步动作（移动/挖掘/放置）完成事件的文案组装（纯 Java,无 Minecraft 依赖,便于 JUnit 单测）。
 *
 * <p>事件文本最终作为 {@code [Event] …} user 消息注入对话,把动作的真实结果
 * 延迟送达给模型（配合 AgentRuntime 的 PENDING_ACTIONS 暂停/恢复机制）。
 * 文案要点：到达事件必须报告与目标的垂直差（到达判定只看水平距离,模型
 * 若不知道自己在目标头顶 9 格,会误以为"到了却挖不了"）。
 */
public final class ActionEvents {
	private ActionEvents() {
	}

	/** 到达事件文本。dy = 目标 y − 实际 y（正 = 目标在上方）。 */
	public static String arrivalText(int x, int y, int z, int dy, boolean teleported) {
		StringBuilder sb = new StringBuilder("Arrived at (").append(x).append(',').append(y)
				.append(',').append(z).append(')');
		if (teleported) {
			sb.append(" (was stuck on the way and got teleported)");
		}
		if (Math.abs(dy) >= 2) {
			sb.append("; note: the target point is ").append(Math.abs(dy))
					.append(" blocks ").append(dy > 0 ? "above" : "below")
					.append(" you — walking does not change altitude, so ")
					.append(dy < 0 ? "dig down to reach it or pick a surface-level target"
							: "you need to climb/pillar up to reach it");
		}
		return sb.toString();
	}

	/** 挖掘完成事件文本（掉落物由 AgentRuntime 按背包差分补充）。 */
	public static String miningCompleteText(int x, int y, int z) {
		return "Mining complete at (" + x + "," + y + "," + z + ") — the block is broken.";
	}

	/** 挖掘中止（走出触及范围——目标在站立点够不到,典型是"在目标头顶到达"）。 */
	public static String miningAbortedRangeText(int x, int y, int z) {
		return "Mining aborted: (" + x + "," + y + "," + z + ") is not within interaction range "
				+ "from where the assistant stopped (it is likely above/below the reachable spot). "
				+ "Get closer by digging toward it, or pick a block the assistant can stand next to.";
	}

	/** 挖掘目标方块已消失（被别人挖掉/爆炸等）。 */
	public static String miningBlockGoneText(int x, int y, int z) {
		return "Mining finished early: the block at (" + x + "," + y + "," + z + ") is already gone.";
	}

	/** 挖掘完成但掉落物尚未入包的说明（真实场景:掉落物落在矿柱里,人还没站上去）。 */
	public static String noPickupYetNote() {
		return " (the drops are not in my inventory yet — they lie at/in the mined shaft; "
				+ "I collect them by standing on them, so check my inventory in the Assistant State next round)";
	}

	/** 移动被停止（player_stop / 中断 / 新指令接管）。 */
	public static String stoppedText() {
		return "Movement stopped before reaching the target (cancelled).";
	}

	/**
	 * 背包差分文本：{@code picked up: cobblestone×3, lost: stick×1};
	 * 无变化返回 null（调用方省略该句）。before/after 为 物品名→数量 快照。
	 */
	public static String inventoryDiffText(Map<String, Integer> before, Map<String, Integer> after) {
		if (before == null || after == null) {
			return null;
		}
		Map<String, Long> delta = new TreeMap<>();
		before.forEach((item, n) -> delta.merge(item, -(long) n, Long::sum));
		after.forEach((item, n) -> delta.merge(item, (long) n, Long::sum));
		StringBuilder gained = new StringBuilder();
		StringBuilder lost = new StringBuilder();
		for (Map.Entry<String, Long> e : delta.entrySet()) {
			if (e.getValue() > 0) {
				appendItem(gained, e.getKey(), e.getValue());
			} else if (e.getValue() < 0) {
				appendItem(lost, e.getKey(), -e.getValue());
			}
		}
		StringBuilder sb = new StringBuilder();
		if (gained.length() > 0) {
			sb.append(" picked up: ").append(gained);
		}
		if (lost.length() > 0) {
			sb.append(sb.length() > 0 ? ";" : "").append(" used/lost: ").append(lost);
		}
		return sb.length() > 0 ? sb.toString() : null;
	}

	private static void appendItem(StringBuilder sb, String item, long n) {
		if (sb.length() > 0) {
			sb.append(", ");
		}
		sb.append(item).append('×').append(n);
	}
}
