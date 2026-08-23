package com.swaydy.opencraft.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 循环守卫的纯 Java 单测（无 Minecraft 运行时）：
 * 1. {@link RepeatToolGuard}：连续相同调用按阈值升级提醒、换参数/换工具重置、reset 清空；
 * 2. {@link LlmRetryPolicy}：错误分类（可重试/不可重试）与退避延迟边界；
 * 3. {@link ToolResultPruner}：超长结果保头尾裁中间，短结果原样；
 * 4. {@link TaskPlan}：任务计划解析/格式化/摘要。
 */
class AgentLoopGuardsTest {

	// ------------------------------------------------------------------
	// RepeatToolGuard
	// ------------------------------------------------------------------

	@Test
	void repeatGuardEscalatesAtThresholds() {
		RepeatToolGuard guard = new RepeatToolGuard();
		assertNull(guard.observe("player_goto", "{\"x\":1,\"y\":2,\"z\":3}"), "第 1 次不提醒");
		assertNull(guard.observe("player_goto", "{\"x\":1,\"y\":2,\"z\":3}"), "第 2 次不提醒");
		String gentle = guard.observe("player_goto", "{\"x\":1,\"y\":2,\"z\":3}");
		assertNotNull(gentle, "第 3 次触发温和提醒");
		assertTrue(gentle.toLowerCase().contains("repeat"), "温和提醒应提及重复");
		assertFalse(gentle.contains("player_goto"), "温和提醒不点名工具");
		String detailed5 = guard.observe("player_goto", "{\"x\":1,\"y\":2,\"z\":3}");
		assertNull(detailed5, "第 4 次不提醒");
		detailed5 = guard.observe("player_goto", "{\"x\":1,\"y\":2,\"z\":3}");
		assertNotNull(detailed5, "第 5 次触发详细提醒");
		assertTrue(detailed5.contains("player_goto"), "详细提醒应点名工具");
		assertTrue(detailed5.contains("5"), "详细提醒应包含连续次数");
		assertNull(guard.observe("player_goto", "{\"x\":1,\"y\":2,\"z\":3}"), "第 6 次不提醒");
		assertNull(guard.observe("player_goto", "{\"x\":1,\"y\":2,\"z\":3}"), "第 7 次不提醒");
		assertNotNull(guard.observe("player_goto", "{\"x\":1,\"y\":2,\"z\":3}"), "第 8 次再次触发详细提醒");
	}

	@Test
	void repeatGuardDifferentArgumentsResets() {
		RepeatToolGuard guard = new RepeatToolGuard();
		guard.observe("player_goto", "{\"x\":1,\"y\":2,\"z\":3}");
		guard.observe("player_goto", "{\"x\":1,\"y\":2,\"z\":3}");
		// 参数变化 → 链条重置（这是"不同调用"，不该误报）
		assertNull(guard.observe("player_goto", "{\"x\":9,\"y\":9,\"z\":9}"));
		assertNull(guard.observe("player_goto", "{\"x\":9,\"y\":9,\"z\":9}"));
		assertNotNull(guard.observe("player_goto", "{\"x\":9,\"y\":9,\"z\":9}"), "新链条到第 3 次才提醒");
	}

	@Test
	void repeatGuardDifferentToolResets() {
		RepeatToolGuard guard = new RepeatToolGuard();
		guard.observe("player_goto", "{\"x\":1,\"y\":2,\"z\":3}");
		guard.observe("player_goto", "{\"x\":1,\"y\":2,\"z\":3}");
		// 换工具 → 链条重置
		guard.observe("player_find", "{\"target\":\"log\"}");
		assertNull(guard.observe("player_find", "{\"target\":\"log\"}"));
		assertNotNull(guard.observe("player_find", "{\"target\":\"log\"}"), "player_find 自己连续 3 次才提醒");
	}

	@Test
	void repeatGuardCanonicalizesKeyOrder() {
		RepeatToolGuard guard = new RepeatToolGuard();
		// 键序不同但内容相同 → 判为相同调用（防止模型换个键序就绕过检测）
		guard.observe("player_goto", "{\"x\":1,\"y\":2,\"z\":3}");
		guard.observe("player_goto", "{\"z\":3,\"y\":2,\"x\":1}");
		assertNotNull(guard.observe("player_goto", "{\"x\":1,\"y\":2,\"z\":3}"), "键序不同也算重复");
	}

	@Test
	void repeatGuardHandlesMalformedJsonAndReset() {
		RepeatToolGuard guard = new RepeatToolGuard();
		guard.observe("player_mine", "{\"x\":1,"); // 非法 JSON → 按原文兜底，仍能稳定判定
		guard.observe("player_mine", "{\"x\":1,");
		assertNotNull(guard.observe("player_mine", "{\"x\":1,"), "非法 JSON 原文一致也算重复");
		guard.reset();
		assertNull(guard.observe("player_mine", "{\"x\":1,"), "reset 后重新计数");
		assertEquals(1, guard.currentCount(), "reset 后从 1 重新累计");
	}

	@Test
	void repeatGuardEmptyArgumentsCountAsSame() {
		RepeatToolGuard guard = new RepeatToolGuard();
		assertNull(guard.observe("player_stop", null));
		assertNull(guard.observe("player_stop", ""));
		assertNotNull(guard.observe("player_stop", "  "), "空参数连续 3 次也算重复");
	}

	// ------------------------------------------------------------------
	// LlmRetryPolicy
	// ------------------------------------------------------------------

	@Test
	void retryPolicyClassifiesErrors() {
		assertTrue(LlmRetryPolicy.retryable("HTTP 429: rate limited"), "限流可重试");
		assertTrue(LlmRetryPolicy.retryable("HTTP 500: internal error"), "5xx 可重试");
		assertTrue(LlmRetryPolicy.retryable("HTTP 503 Service Unavailable"), "503 可重试");
		assertTrue(LlmRetryPolicy.retryable("HttpTimeoutException: request timed out"), "超时可重试");
		assertTrue(LlmRetryPolicy.retryable("ConnectException: Connection refused"), "连接失败可重试");
		assertTrue(LlmRetryPolicy.retryable("IOException: stream closed"), "IO 错误可重试");
		assertTrue(LlmRetryPolicy.retryable((String) null), "未知错误保守可重试");
		assertTrue(LlmRetryPolicy.retryable(""), "空错误保守可重试");
		assertFalse(LlmRetryPolicy.retryable("HTTP 401: unauthorized"), "鉴权失败不可重试");
		assertFalse(LlmRetryPolicy.retryable("HTTP 400: bad request"), "参数错误不可重试");
		assertFalse(LlmRetryPolicy.retryable("无法解析响应 JSON: {bad"), "响应解析失败不可重试");
		assertFalse(LlmRetryPolicy.retryable("响应中没有找到 choices"), "响应结构错误不可重试");
		assertFalse(LlmRetryPolicy.retryable("request-stalled: 服务器在超过 15 秒内没有返回任何数据"),
				"SSE 读取看门狗超时（服务端长时间无响应）不可重试，快速失败让玩家重问/中断");
	}

	@Test
	void retryPolicyClassifiesCodes() {
		// 生产路径按新客户端 LlmClient 的稳定错误码路由（与 dsh-llm-retry 默认可重试集一致）
		assertTrue(LlmRetryPolicy.retryableCode(com.swaydy.opencraft.ai.LlmClient.Codes.EMPTY_RESPONSE),
				"空响应（退化完成）可重试");
		assertTrue(LlmRetryPolicy.retryableCode(com.swaydy.opencraft.ai.LlmClient.Codes.RATE_LIMIT), "限流可重试");
		assertTrue(LlmRetryPolicy.retryableCode(com.swaydy.opencraft.ai.LlmClient.Codes.SERVER), "服务端错误可重试");
		assertTrue(LlmRetryPolicy.retryableCode(com.swaydy.opencraft.ai.LlmClient.Codes.TIMEOUT), "超时可重试");
		assertTrue(LlmRetryPolicy.retryableCode(com.swaydy.opencraft.ai.LlmClient.Codes.TRANSPORT), "传输错误可重试");
		assertTrue(LlmRetryPolicy.retryableCode(null), "未知错误保守可重试");

		assertFalse(LlmRetryPolicy.retryableCode(com.swaydy.opencraft.ai.LlmClient.Codes.STALLED),
				"SSE 看门狗（连接后不吐数据）不可重试");
		assertFalse(LlmRetryPolicy.retryableCode(com.swaydy.opencraft.ai.LlmClient.Codes.AUTH), "鉴权失败不可重试");
		assertFalse(LlmRetryPolicy.retryableCode(com.swaydy.opencraft.ai.LlmClient.Codes.QUOTA), "配额耗尽不可重试");
		assertFalse(LlmRetryPolicy.retryableCode(com.swaydy.opencraft.ai.LlmClient.Codes.INVALID_REQUEST),
				"参数错误不可重试");
		assertFalse(LlmRetryPolicy.retryableCode(com.swaydy.opencraft.ai.LlmClient.Codes.CONTEXT_WINDOW_EXCEEDED),
				"上下文超限不可重试");
		assertFalse(LlmRetryPolicy.retryableCode(com.swaydy.opencraft.ai.LlmClient.Codes.MALFORMED_RESPONSE),
				"响应解析失败不可重试");
		assertFalse(LlmRetryPolicy.retryableCode("HTTP_418"), "其它状态码不可重试");
	}

	@Test
	void retryPolicyDelayWithinBounds() {
		for (int retry = 1; retry <= 4; retry++) {
			long delay = LlmRetryPolicy.delayMs(retry);
			assertTrue(delay >= 1 && delay <= LlmRetryPolicy.MAX_DELAY_MS,
					"延迟应在 (0, MAX_DELAY_MS] 内，实际 " + delay);
		}
		// 第 1 次约 500ms±10%
		long first = LlmRetryPolicy.delayMs(1);
		assertTrue(first >= 450 && first <= 550, "首次退避约 500ms±10%，实际 " + first);
		// 指数增长：第 2 次约 1000ms±10%
		long second = LlmRetryPolicy.delayMs(2);
		assertTrue(second >= 900 && second <= 1100, "第二次退避约 1000ms±10%，实际 " + second);
		// 上限封顶
		assertTrue(LlmRetryPolicy.delayMs(20) <= LlmRetryPolicy.MAX_DELAY_MS);
	}

	// ------------------------------------------------------------------
	// ToolResultPruner
	// ------------------------------------------------------------------

	@Test
	void prunerKeepsShortTextUntouched() {
		String shortText = "position: x=1, y=2, z=3, still";
		assertEquals(shortText, ToolResultPruner.prune(shortText));
		assertEquals("", ToolResultPruner.prune(null));
	}

	@Test
	void prunerKeepsHeadAndTailForLongText() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 5000; i++) {
			sb.append((char) ('a' + (i % 26)));
		}
		String pruned = ToolResultPruner.prune(sb.toString());
		assertTrue(pruned.length() < 2000, "裁剪后应远小于原文");
		assertTrue(pruned.startsWith("abc"), "应保留头部（原文从头是 abc… 循环字母）");
		char lastChar = sb.charAt(sb.length() - 1);
		assertTrue(pruned.endsWith(String.valueOf(lastChar)), "应保留原文尾部字符");
		assertTrue(pruned.contains("omitted"), "应包含省略标记");
	}

	@Test
	void prunerTagsResultWithStatus() {
		String ok = ToolResultPruner.toModelText("player_find", true, "position: x=1");
		assertTrue(ok.startsWith("[player_find success] "), "成功结果应带成功标记: " + ok);
		String fail = ToolResultPruner.toModelText("player_mine", false, "target too far from owner");
		assertTrue(fail.startsWith("[player_mine failure] "), "失败结果应带失败标记: " + fail);
		// 长结果：标记头保留 + 正文裁剪
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 5000; i++) {
			sb.append('x');
		}
		String tagged = ToolResultPruner.toModelText("player_find", true, sb.toString());
		assertTrue(tagged.startsWith("[player_find success] "));
		assertTrue(tagged.length() < 2000);
	}

	// ------------------------------------------------------------------
	// TaskPlan
	// ------------------------------------------------------------------

	@Test
	void taskPlanParsesAndFormats() {
		JsonObject args = JsonParser.parseString("""
				{"steps":[
				  {"content":"Walk to the cave","status":"in_progress"},
				  {"content":"Mine 5 iron ore","status":"pending"},
				  {"content":"Craft an iron ingot","status":"completed"}
				]}""").getAsJsonObject();
		TaskPlan plan = TaskPlan.fromJson(args);
		assertNotNull(plan, "合法计划应解析成功");
		String formatted = plan.format();
		assertTrue(formatted.contains("\"content\":\"Walk to the cave\""), "JSON 含步骤内容: " + formatted);
		assertTrue(formatted.contains("\"status\":\"in_progress\""), "含进行中状态");
		assertTrue(formatted.contains("\"content\":\"Mine 5 iron ore\""), "含待办步骤");
		assertTrue(formatted.contains("\"content\":\"Craft an iron ingot\""), "含完成步骤");
		assertTrue(formatted.contains("\"status\":\"completed\""), "含完成状态");
		assertEquals("3 steps (1 done, 1 in progress, 1 pending)", plan.summary());
	}

	@Test
	void taskPlanRejectsInvalidInput() {
		assertNull(TaskPlan.fromJson(null), "空参数返回 null");
		assertNull(TaskPlan.fromJson(new JsonObject()), "无 steps 返回 null");
		assertNull(TaskPlan.fromJson(JsonParser.parseString("{\"steps\":[]}").getAsJsonObject()),
				"空数组返回 null");
		assertNull(TaskPlan.fromJson(JsonParser.parseString(
				"{\"steps\":[{\"content\":\"  \",\"status\":\"pending\"}]}").getAsJsonObject()),
				"空白 content 拒绝");
		assertNull(TaskPlan.fromJson(JsonParser.parseString(
				"{\"steps\":[{\"content\":\"a\",\"status\":\"done\"}]}").getAsJsonObject()),
				"非法 status 拒绝");
		assertNull(TaskPlan.fromJson(JsonParser.parseString(
				"{\"steps\":[{\"content\":\"a\",\"status\":\"pending\"},"
						+ "{\"content\":\"a\",\"status\":\"completed\"}]}").getAsJsonObject()),
				"重复 content 拒绝");
		assertNull(TaskPlan.fromJson(JsonParser.parseString(
				"{\"steps\":\"nope\"}").getAsJsonObject()), "steps 非数组拒绝");
	}

	@Test
	void taskPlanKeepsContentTrimmed() {
		TaskPlan plan = TaskPlan.fromJson(JsonParser.parseString(
				"{\"steps\":[{\"content\":\"  mine stone  \",\"status\":\"in_progress\"}]}").getAsJsonObject());
		assertNotNull(plan);
		assertTrue(plan.format().contains("mine stone"), "content 应被去除首尾空白");
	}

	@Test
	void taskPlanHasUnfinishedDetectsPendingSteps() {
		TaskPlan allDone = TaskPlan.fromJson(JsonParser.parseString("""
				{"steps":[
				  {"content":"mine","status":"completed"},
				  {"content":"craft","status":"completed"}
				]}""").getAsJsonObject());
		assertFalse(allDone.hasUnfinished(), "全部 completed 应无未完成步骤");

		TaskPlan withPending = TaskPlan.fromJson(JsonParser.parseString("""
				{"steps":[
				  {"content":"mine","status":"completed"},
				  {"content":"craft","status":"pending"}
				]}""").getAsJsonObject());
		assertTrue(withPending.hasUnfinished(), "pending 步骤应算未完成");

		TaskPlan withActive = TaskPlan.fromJson(JsonParser.parseString(
				"{\"steps\":[{\"content\":\"mine\",\"status\":\"in_progress\"}]}").getAsJsonObject());
		assertTrue(withActive.hasUnfinished(), "in_progress 步骤应算未完成");
	}

	// ------------------------------------------------------------------
	// 5. StallGuard：连续纯观察触发提醒、真实动作重置、bumped 防重复
	// ------------------------------------------------------------------

	@Test
	void stallGuardNudgesAfterRepeatedReadOnlyRounds() {
		StallGuard guard = new StallGuard(3);
		// 前两轮纯观察：不触发
		assertNull(guard.observe(java.util.List.of("player_find"), false), "第 1 轮观察不应触发");
		assertNull(guard.observe(java.util.List.of("player_find"), false),
				"第 2 轮观察不应触发");
		assertEquals(2, guard.streak());
		// 第三轮纯观察：触发提醒
		String nudge = guard.observe(java.util.List.of("player_find"), false);
		assertNotNull(nudge, "第 3 轮纯观察应触发停滞提醒");
		assertTrue(nudge.toLowerCase().contains("stall"), "提醒应包含停滞提示");
	}

	@Test
	void stallGuardResetsOnStateChangingAction() {
		StallGuard guard = new StallGuard(3);
		guard.observe(java.util.List.of("player_find"), false);
		guard.observe(java.util.List.of("player_find"), false);
		// 出现真实动作（即使只是下达指令）：重置，不再累计
		assertNull(guard.observe(java.util.List.of("player_goto"), true), "真实动作轮返回 null 并重置");
		assertEquals(0, guard.streak(), "真实动作后停滞计数应归零");
		// 重置后重新观察也能再次触发（bumped 已清除）
		guard.observe(java.util.List.of("player_find"), false);
		guard.observe(java.util.List.of("player_find"), false);
		assertNotNull(guard.observe(java.util.List.of("player_find"), false),
				"重置后再次连续观察应能再次触发");
	}

	@Test
	void stallGuardOnlyBumpsOnceUntilReset() {
		StallGuard guard = new StallGuard(2);
		assertNull(guard.observe(java.util.List.of("player_find"), false));
		assertNotNull(guard.observe(java.util.List.of("player_find"), false),
				"第 2 轮应触发");
		// 已触发后再观察：不再重复提醒（bumped 防刷）
		assertNull(guard.observe(java.util.List.of("player_find"), false), "bumped 后不再重复提醒");
		assertEquals(3, guard.streak(), "计数继续增长（便于日志）");
	}
}
