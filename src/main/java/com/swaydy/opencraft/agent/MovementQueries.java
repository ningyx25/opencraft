package com.swaydy.opencraft.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * 移动类工具参数的纯 Java 判定（无 Minecraft 依赖，便于 JUnit 单测）。
 *
 * <p>目前只有一项：{@link #isSameGotoTarget}——判断一次 {@code player_goto}
 * 调用是否与「当前在途的手动移动目标」指向同一方块。这是重复调用守卫的
 * 轮询豁免基础：异步移动需要数秒才能到达，模型在等待期间重复下达同一目标
 * 是"再确认"而不是"失败死循环"，不应被 {@link RepeatToolGuard} 劝停。
 */
public final class MovementQueries {
	private MovementQueries() {
	}

	/**
	 * goto 参数 (x,y,z) 是否与当前在途移动目标一致（容差 {@code tolerance} 格）。
	 *
	 * <p>工具下达目标时用的是方块中心 {@code (x+0.5, y, z+0.5)}（见
	 * PlayerActionsPlugin.gotoTool），这里把参数同样换算到方块中心再比对。
	 * 参数缺失/非法返回 false（豁免不生效，走常规执行与守卫路径）。
	 *
	 * @param args     工具参数 JSON（x/y/z 整数，数字或数字字符串——与 ToolArgs 语义一致）
	 * @param targetX/Y/Z 当前移动目标（工具下达时的方块中心坐标）
	 * @param tolerance 判为同一目标的容差（格）
	 */
	public static boolean isSameGotoTarget(JsonObject args, double targetX, double targetY,
	                                        double targetZ, double tolerance) {
		if (args == null) {
			return false;
		}
		Double x = intArg(args, "x");
		Double y = intArg(args, "y");
		Double z = intArg(args, "z");
		if (x == null || y == null || z == null) {
			return false;
		}
		return Math.abs(x + 0.5 - targetX) <= tolerance
				&& Math.abs(y - targetY) <= tolerance
				&& Math.abs(z + 0.5 - targetZ) <= tolerance;
	}

	/** 读取整数参数（接受数字或数字字符串，与 ToolArgs.intOf 一致）；缺失/非法返回 null。 */
	private static Double intArg(JsonObject args, String key) {
		JsonElement el = args.get(key);
		if (el == null || !el.isJsonPrimitive()) {
			return null;
		}
		JsonPrimitive p = el.getAsJsonPrimitive();
		try {
			if (p.isNumber()) {
				return (double) p.getAsInt();
			}
			if (p.isString()) {
				return (double) (int) Double.parseDouble(p.getAsString());
			}
		} catch (NumberFormatException ignored) {
			// 非数字字符串：视为缺失
		}
		return null;
	}
}
