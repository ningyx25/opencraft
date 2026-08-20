package com.swaydy.opencraft.agent;

import java.util.List;
import java.util.Set;

/**
 * 停滞守卫：打断「连续多轮只做纯观察、而世界/背包状态毫无变化」的死循环
 * （例如模型反复 player_look / player_inventory 却拿不出结论、也不下达真实动作，
 * 在慢/弱 API 下尤其常见，表现为一次提问跑很多轮却不收束）。
 *
 * <p>达到阈值（默认连续 3 轮纯观察）注入一次提醒，让模型二选一：给出结论结束任务，
 * 或执行真实动作（先 player_find 拿精确坐标再动手）。继续空转则由 maxToolRounds
 * 的最后一轮总结兜底，不会无限跑。
 *
 * <p>纯 Java、无 Minecraft 依赖，便于 JUnit 单测。每个任务（一次玩家提问）应新建一个实例
 * （或调用 {@link #reset()}），跨任务不累计。
 */
public final class StallGuard {
	/** 默认阈值：连续 {@value #DEFAULT_STALL_LIMIT} 轮纯观察 → 提醒一次。 */
	private static final int DEFAULT_STALL_LIMIT = 3;

	/**
	 * 只读工具集合：调用这些工具不会改变世界/背包状态，纯观察。
	 * 同时覆盖玩家形态（player_look/player_inventory）与实体形态遗留（look_around/inspect_block）。
	 */
	private static final Set<String> READ_ONLY_TOOLS = Set.of(
			"player_look", "look_around", "player_inventory", "inspect_block");

	private final int stallLimit;
	private int streak = 0;
	private boolean bumped = false;

	public StallGuard() {
		this(DEFAULT_STALL_LIMIT);
	}

	public StallGuard(int stallLimit) {
		if (stallLimit < 1) {
			throw new IllegalArgumentException("stallLimit 必须为正整数");
		}
		this.stallLimit = stallLimit;
	}

	/**
	 * 记录一轮的工具调用与状态变化。
	 *
	 * @param toolNames     本轮调用的工具名列表（本次观察只在“有工具被调用”的分支调用；
	 *                      防御性地把空列表当作“直接给文本回复”，不当作停滞）
	 * @param stateChanged  本轮是否产生了实际状态变化（移动/挖掘/放置/合成/递物等任意非只读工具
	 *                      都算；false = 纯观察）
	 * @return 撞阈值时要注入给模型的提醒文本，否则返回 null
	 */
	public String observe(List<String> toolNames, boolean stateChanged) {
		if (stateChanged || toolNames == null || toolNames.isEmpty()
				|| !toolNames.stream().allMatch(READ_ONLY_TOOLS::contains)) {
			// 本轮做了实事（或直接给结论）：视为有进展，重置停滞计数
			reset();
			return null;
		}
		streak++;
		if (streak >= stallLimit && !bumped) {
			bumped = true;
			return nudge(streak);
		}
		return null;
	}

	/** 清除停滞计数与已提醒标记（新一轮任务开始时也应调用）。 */
	public void reset() {
		streak = 0;
		bumped = false;
	}

	/** 当前连续纯观察轮数。 */
	public int streak() {
		return streak;
	}

	/** 某工具是否为纯观察（只读）工具。 */
	public static boolean isReadOnly(String toolName) {
		return toolName != null && READ_ONLY_TOOLS.contains(toolName);
	}

	private static String nudge(int rounds) {
		return "【停滞提醒】你已经连续 " + rounds + " 轮只用观察工具（player_look / player_inventory），"
				+ "而世界和背包状态没有任何变化——这个任务没有取得进展。请立即二选一："
				+ "① 用一两句话向玩家说明现状并结束任务（不要再调用工具）；"
				+ "② 执行一个真实动作：先用 player_find 拿到目标的精确坐标，"
				+ "再 player_goto / player_mine / player_place 动手做。"
				+ "不要继续反复调用观察工具。";
	}
}
