package com.swaydy.opencraft.agent;

import com.google.gson.JsonObject;
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
 * ToolExecutor 工具执行管线的纯 Java 单测（无 Minecraft 运行时）：
 * 未知工具、ask_player 确认短路、无效提问继续执行、每批上限、task_plan 认领、停滞守卫提醒。
 * 管线在 AgentRuntime 里只经 gametest 覆盖，抽成独立类后在此直接测契约。
 */
class ToolExecutorTest {

	private static LlmClient.ToolCallBlock call(String id, String name, String args) {
		return new LlmClient.ToolCallBlock(id, name, args);
	}

	private static LoopSession session() {
		return new LoopSession(null, null, null,
				new AgentDefinition("test", "test", List.of(), "", 10),
				new ArrayList<>(), null, null, null, null, false, 0);
	}

	private static String toolContent(LlmClient.Message m) {
		List<LlmClient.ToolResultBlock> results = m.toolResults();
		return results.isEmpty() ? "" : results.get(0).content();
	}

	private static final class Recorder implements ToolExecutor.Host {
		boolean paused;
		String question;
		int nextRound;
		int notified;
		int deferred;

		@Override
		public void pauseForAnswer(LoopSession ctx, String question, int nextRound) {
			this.paused = true;
			this.question = question;
			this.nextRound = nextRound;
		}

		@Override
		public void registerPendingAction(LoopSession ctx, String toolName, JsonObject args, int round) {
			this.deferred++;
		}

		@Override
		public boolean isRedundantInFlightGoto(com.swaydy.opencraft.assistant.AiAssistant assistant,
		                                       String toolName, JsonObject args) {
			return false;
		}

		@Override
		public boolean isAsyncActionTool(String toolName) {
			return false;
		}

		@Override
		public void notifyToolExecuted(String toolName, ToolResult result) {
			this.notified++;
		}
	}

	@Test
	void unknownToolReturnsErrorAndNotifies() {
		LoopSession session = session();
		Recorder host = new Recorder();
		List<LlmClient.Message> results =
				ToolExecutor.executeBatch(session, List.of(call("c1", "no_such_tool", "{}")), 0, host);
		assertEquals(1, results.size());
		assertTrue(toolContent(results.get(0)).contains("Unknown tool"));
		assertEquals(1, host.notified);
	}

	@Test
	void validAskPausesAndSkipsRestOfBatch() {
		LoopSession session = session();
		Recorder host = new Recorder();
		List<LlmClient.Message> results = ToolExecutor.executeBatch(session, List.of(
				call("a1", "ask_player", "{\"question\":\"Mine the chest?\"}"),
				call("a2", "no_such_tool", "{}")), 0, host);
		assertTrue(host.paused);
		assertEquals("Mine the chest?", host.question);
		assertEquals(1, host.nextRound);
		assertEquals(1, results.size());
		assertEquals(1, host.notified);
	}

	@Test
	void invalidAskErrorsAndContinuesBatch() {
		LoopSession session = session();
		Recorder host = new Recorder();
		List<LlmClient.Message> results = ToolExecutor.executeBatch(session, List.of(
				call("a1", "ask_player", "{\"question\":\"  \"}"),
				call("a2", "no_such_tool", "{}")), 0, host);
		assertFalse(host.paused);
		assertEquals(2, results.size());
		assertTrue(toolContent(results.get(0)).contains("Please provide the question"));
		assertTrue(toolContent(results.get(1)).contains("Unknown tool"));
	}

	@Test
	void capsToolsPerBatch() {
		LoopSession session = session();
		List<LlmClient.Message> results = new ArrayList<>();
		List<LlmClient.ToolCallBlock> calls = new ArrayList<>();
		for (int i = 0; i < ToolExecutor.MAX_TOOLS_PER_ROUND + 1; i++) {
			calls.add(call("c" + i, "tool_" + i, "{}"));
		}
		results.addAll(ToolExecutor.executeBatch(session, calls, 0, new Recorder()));
		assertEquals(ToolExecutor.MAX_TOOLS_PER_ROUND + 1, results.size());
		assertTrue(toolContent(results.get(results.size() - 1)).contains("per-round tool call limit"));
	}

	@Test
	void taskPlanIsClaimedAndUpdatesSession() {
		LoopSession session = session();
		Recorder host = new Recorder();
		List<LlmClient.Message> results = ToolExecutor.executeBatch(session, List.of(
				call("p1", "task_plan",
						"{\"steps\":[{\"content\":\"gather wood\",\"status\":\"in_progress\"}]}")), 0, host);
		assertEquals(1, results.size());
		assertTrue(toolContent(results.get(0)).contains("Task plan updated"));
		assertNotNull(session.plan);
		assertTrue(session.planUpdatedThisRound);
	}

	@Test
	void stallWarningAfterRepeatedReadOnlyRounds() {
		LoopSession session = session();
		Recorder host = new Recorder();
		List<LlmClient.Message> first =
				ToolExecutor.executeBatch(session, List.of(call("s1", "player_find", "{}")), 0, host);
		assertFalse(first.stream().anyMatch(m -> m.text().contains("[Stall warning]")));
		ToolExecutor.executeBatch(session, List.of(call("s2", "player_find", "{}")), 1, host);
		List<LlmClient.Message> third =
				ToolExecutor.executeBatch(session, List.of(call("s3", "player_find", "{}")), 2, host);
		assertTrue(third.stream().anyMatch(m -> m.text().contains("[Stall warning]")));
	}
}
