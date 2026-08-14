package com.swaydy.opencraft.ai;

/**
 * AI 助手通过回复中的 [ACTION: ...] 标记触发的游戏内动作。
 *
 * 支持的语法（在回复中单独一行或末尾，可多个）：
 * <pre>
 * [ACTION: give minecraft:dirt 64]    —— 给予玩家物品（物品ID 数量）
 * [ACTION: time day|night|noon]       —— 设置游戏时间
 * [ACTION: heal]                      —— 治疗玩家
 * [ACTION: feed]                      —— 恢复饥饿
 * [ACTION: xp 10]                     —— 给予经验等级
 * [ACTION: mode follow|stay]          —— 切换助手的跟随/待命模式
 * [ACTION: tp]                        —— 让助手瞬移到玩家身边（跨维度）
 * [ACTION: weather clear|rain|thunder]—— 设置天气
 * </pre>
 */
public record AiAction(Type type, String itemId, int amount, String mode, String weather) {

	public enum Type {
		GIVE, TIME, HEAL, FEED, XP, MODE, TELEPORT, WEATHER, UNKNOWN
	}

	public static AiAction give(String itemId, int amount) {
		return new AiAction(Type.GIVE, itemId, amount, null, null);
	}

	public static AiAction time(String mode) {
		return new AiAction(Type.TIME, mode, 0, null, null);
	}

	public static AiAction simple(Type type) {
		return new AiAction(type, null, 0, null, null);
	}

	public static AiAction xp(int amount) {
		return new AiAction(Type.XP, null, amount, null, null);
	}

	public static AiAction mode(String mode) {
		return new AiAction(Type.MODE, null, 0, mode, null);
	}

	public static AiAction weather(String weather) {
		return new AiAction(Type.WEATHER, null, 0, null, weather);
	}

	/** 动作的中文说明（用于聊天反馈与日志）。 */
	public String describe() {
		return switch (type) {
			case GIVE -> "给予物品 " + itemId + " ×" + amount;
			case TIME -> "设置时间 " + itemId;
			case HEAL -> "治疗玩家";
			case FEED -> "恢复饥饿";
			case XP -> "给予经验 " + amount + " 级";
			case MODE -> "切换为" + ("follow".equalsIgnoreCase(mode) ? "跟随" : "待命") + "模式";
			case TELEPORT -> "瞬移到玩家身边";
			case WEATHER -> "设置天气 " + weather;
			case UNKNOWN -> "未知动作";
		};
	}
}
