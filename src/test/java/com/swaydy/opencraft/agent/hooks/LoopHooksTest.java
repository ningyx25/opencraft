package com.swaydy.opencraft.agent.hooks;

import com.google.gson.JsonObject;
import com.swaydy.opencraft.agent.LoopSession;
import com.swaydy.opencraft.ai.LlmClient;
import com.swaydy.opencraft.plugins.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LoopHook 生命周期钩子装配的纯 Java 单测（无 Minecraft 运行时）：
 * 验证默认组合、核心工具 schema 贡献、ask_player 的整批确认短路，以及重复调用守卫经
 * afterTool 钩子追加提醒——这些是 AgentRuntime 薄驱动与策略钩子之间的新契约。
 */
class LoopHooksTest {

	private static LlmClient.ToolCallBlock call(String id, String name, String args) {
		return new LlmClient.ToolCallBlock(id, name, args);
	}

	/** 默认组合含 5 个钩子，且贡献且仅贡献 ask_player / task_plan 两个核心工具 schema。 */
	@Test
	void defaultCompositionContributesCoreTools() {
		List<LoopHook> hooks = LoopHooks.createDefaults();
		assertEquals(5, hooks.size());

		List<String> toolNames = new ArrayList<>();
		for (LoopHook h : hooks) {
			for (JsonObject schema : h.tools()) {
				toolNames.add(schema.getAsJsonObject("function").get("name").getAsString());
			}
		}
		assertTrue(toolNames.contains("ask_player"));
		assertTrue(toolNames.contains("task_plan"));
		assertEquals(2, toolNames.size());
	}

	/** 每次任务新建一套钩子（per-task 状态隔离）。 */
	@Test
	void defaultsAreFreshPerSession() {
		assertFalse(LoopHooks.createDefaults() == LoopHooks.createDefaults());
	}

	/** 有效 ask_player：整批短路认领、返回暂停等待玩家回答。 */
	@Test
	void validAskClaimsBatchAndPauses() {
		AskPlayerHook hook = new AskPlayerHook();
		List<LlmClient.ToolCallBlock> calls = List.of(
				call("c1", "player_find", "{}"),
				call("c2", "ask_player", "{\"question\":\"Should I mine the chest?\"}"));
		BatchClaim claim = hook.beforeBatch(null, calls);
		assertTrue(claim.claimed());
		assertEquals("c2", claim.call().id());
		assertTrue(claim.handle().pausesForAnswer());
		assertNotNull(claim.handle().askQuestion());
		assertTrue(claim.handle().result().ok());
	}

	/** 缺参 ask_player：认领但不暂停（回错误结果，其余工具照常执行）。 */
	@Test
	void invalidAskClaimsButDoesNotPause() {
		AskPlayerHook hook = new AskPlayerHook();
		BatchClaim claim = hook.beforeBatch(null, List.of(
				call("c9", "ask_player", "{\"question\":\"  \"}")));
		assertTrue(claim.claimed());
		assertFalse(claim.handle().pausesForAnswer());
		assertFalse(claim.handle().result().ok());
	}

	/** 没有 ask_player 的批次不认领。 */
	@Test
	void batchWithoutAskNotClaimed() {
		BatchClaim claim = new AskPlayerHook().beforeBatch(null, List.of(
				call("c1", "player_find", "{}"),
				call("c2", "task_plan", "{\"steps\":[]}")));
		assertFalse(claim.claimed());
	}

	/** 重复调用守卫经 afterTool 钩子：第 3 次完全相同调用追加一条提醒；豁免调用不追加。 */
	@Test
	void repeatHookAppendsReminderAtThreshold() {
		RepeatCallHook hook = new RepeatCallHook();
		List<LlmClient.Message> out = new ArrayList<>();
		LlmClient.ToolCallBlock c = call("t1", "player_goto", "{\"x\":1,\"y\":64,\"z\":2}");
		ToolResult ok = ToolResult.ok("walking");

		hook.afterTool(null, new ToolExec(c, "player_goto", ok, true), out);
		hook.afterTool(null, new ToolExec(c, "player_goto", ok, true), out);
		assertTrue(out.isEmpty(), "前两次不应提醒");
		hook.afterTool(null, new ToolExec(c, "player_goto", ok, true), out);
		assertEquals(1, out.size());
		assertTrue(out.get(0).text().contains("[Reminder]"));
	}

	/** countForRepeat=false（冗余 goto / 成功 task_plan）不计入重复链。 */
	@Test
	void repeatHookIgnoresExemptCalls() {
		RepeatCallHook hook = new RepeatCallHook();
		List<LlmClient.Message> out = new ArrayList<>();
		LlmClient.ToolCallBlock c = call("t1", "player_goto", "{\"x\":1}");
		ToolResult ok = ToolResult.ok("already walking");
		for (int i = 0; i < 5; i++) {
			hook.afterTool(null, new ToolExec(c, "player_goto", ok, false), out);
		}
		assertTrue(out.isEmpty());
	}

	/** task_plan 钩子：合法步骤认领并成功；非法步骤认领但失败（交由重复守卫拦截错误死循环）。 */
	@Test
	void taskPlanHookValidates() {
		TaskPlanHook hook = new TaskPlanHook();
		LoopSession session = new LoopSession(null, null, null, null, new ArrayList<>(),
				null, null, null, null, false, 0);
		ToolHandle good = hook.handleTool(session, call("p1", "task_plan",
				"{\"steps\":[{\"content\":\"gather wood\",\"status\":\"completed\"},"
						+ "{\"content\":\"craft pickaxe\",\"status\":\"in_progress\"}]}"));
		assertTrue(good.isHandled());
		assertTrue(good.result().ok());
		assertNotNull(session.plan);
		assertTrue(session.planUpdatedThisRound);

		ToolHandle bad = hook.handleTool(session, call("p2", "task_plan", "{\"steps\":[]}"));
		assertTrue(bad.isHandled());
		assertFalse(bad.result().ok());

		ToolHandle other = hook.handleTool(null, call("p3", "player_find", "{}"));
		assertFalse(other.isHandled());
	}
}
