package com.swaydy.opencraft.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MovementQueries} 的纯 Java 单测：goto 参数与在途移动目标的比对
 * （含 (x+0.5,z+0.5) 方块中心换算、容差边界、数字字符串、非法参数）。
 */
class MovementQueriesTest {

	/** 工具下达 goto(-155,58,-55) 后,控制器目标为 (-154.5, 58, -54.5)。 */
	private static final double TX = -154.5, TY = 58.0, TZ = -54.5;

	@Test
	void sameTargetWithinTolerance() {
		JsonObject args = JsonParser.parseString("{\"x\":-155,\"y\":58,\"z\":-55}").getAsJsonObject();
		assertTrue(MovementQueries.isSameGotoTarget(args, TX, TY, TZ, 1.0), "同目标应判一致");
	}

	@Test
	void keyOrderDoesNotMatter() {
		JsonObject args = JsonParser.parseString("{\"z\":-55,\"x\":-155,\"y\":58}").getAsJsonObject();
		assertTrue(MovementQueries.isSameGotoTarget(args, TX, TY, TZ, 1.0), "键序不影响判定");
	}

	@Test
	void differentTargetRejected() {
		JsonObject args = JsonParser.parseString("{\"x\":-140,\"y\":70,\"z\":-44}").getAsJsonObject();
		assertFalse(MovementQueries.isSameGotoTarget(args, TX, TY, TZ, 1.0), "不同目标应判不一致");
	}

	@Test
	void toleranceBoundary() {
		// y 偏差恰为容差 1.0 → 判一致;超过 → 不一致
		JsonObject atLimit = JsonParser.parseString("{\"x\":-155,\"y\":59,\"z\":-55}").getAsJsonObject();
		assertTrue(MovementQueries.isSameGotoTarget(atLimit, TX, TY, TZ, 1.0), "偏差=容差应判一致");
		JsonObject overLimit = JsonParser.parseString("{\"x\":-155,\"y\":60,\"z\":-55}").getAsJsonObject();
		assertFalse(MovementQueries.isSameGotoTarget(overLimit, TX, TY, TZ, 1.0),
				"偏差>容差应判不一致");
	}

	@Test
	void numericStringAcceptedLikeToolArgs() {
		// ToolArgs.intOf 接受数字字符串——判定必须与其语义一致,否则豁免漏判
		JsonObject args = JsonParser.parseString("{\"x\":\"-155\",\"y\":\"58\",\"z\":\"-55\"}").getAsJsonObject();
		assertTrue(MovementQueries.isSameGotoTarget(args, TX, TY, TZ, 1.0), "数字字符串应视为同目标");
	}

	@Test
	void malformedArgsRejected() {
		assertFalse(MovementQueries.isSameGotoTarget(null, TX, TY, TZ, 1.0), "null 参数判 false");
		assertFalse(MovementQueries.isSameGotoTarget(new JsonObject(), TX, TY, TZ, 1.0), "缺 x/y/z 判 false");
		JsonObject missingZ = JsonParser.parseString("{\"x\":-155,\"y\":58}").getAsJsonObject();
		assertFalse(MovementQueries.isSameGotoTarget(missingZ, TX, TY, TZ, 1.0), "缺 z 判 false");
		JsonObject bad = JsonParser.parseString("{\"x\":\"abc\",\"y\":58,\"z\":-55}").getAsJsonObject();
		assertFalse(MovementQueries.isSameGotoTarget(bad, TX, TY, TZ, 1.0), "非数字判 false");
	}
}
