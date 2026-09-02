package com.swaydy.opencraft.agent;

import com.swaydy.opencraft.ai.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 历史压缩纯逻辑的单测（无 Minecraft/LLM 依赖）：触发阈值、裁剪、摘要落地与回退。
 * 原逻辑内联在 AgentRuntime 时无直接测试；抽出 HistoryCompactor 后补上。
 */
class HistoryCompactorTest {

	private static List<LlmClient.Message> messages(int n) {
		List<LlmClient.Message> list = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			list.add(LlmClient.Message.user("message-" + i));
		}
		return list;
	}

	@Test
	void needsCompactionUsesDoubleThreshold() {
		assertTrue(HistoryCompactor.needsCompaction(21, 10));
		assertFalse(HistoryCompactor.needsCompaction(20, 10));
		assertFalse(HistoryCompactor.needsCompaction(3, 2));
	}

	@Test
	void keepCountNeverBelowTwo() {
		assertEquals(10, HistoryCompactor.keepCount(10));
		assertEquals(2, HistoryCompactor.keepCount(1));
		assertEquals(2, HistoryCompactor.keepCount(0));
	}

	@Test
	void charsOfIncludesToolResultAndCallPayloads() {
		List<LlmClient.Message> messages = new ArrayList<>();
		String toolResult = "mined 3 cobblestone";
		String callArgs = "{\"x\":1,\"y\":64,\"z\":2}";
		messages.add(LlmClient.Message.toolResult("t1", "mined 3 cobblestone", false));
		messages.add(LlmClient.Message.assistant(List.of(
				new LlmClient.TextBlock("heading over"),
				new LlmClient.ToolCallBlock("c1", "player_goto", callArgs))));
		long textOnly = messages.stream().mapToLong(m -> m.text() == null ? 0 : m.text().length()).sum();
		long full = HistoryCompactor.charsOf(messages);
		assertTrue(full > textOnly);
		assertEquals(toolResult.length() + "heading over".length() + "player_goto".length()
				+ callArgs.length(), full);
	}

	@Test
	void trimKeepsMostRecentMessages() {
		List<LlmClient.Message> recent = HistoryCompactor.trimToRecent(messages(8), 3);
		assertEquals(3, recent.size());
		assertEquals("message-7", recent.get(recent.size() - 1).text());
		assertEquals("message-5", recent.get(0).text());
	}

	@Test
	void trimIsNoOpWhenUnderLimit() {
		List<LlmClient.Message> original = messages(3);
		List<LlmClient.Message> trimmed = HistoryCompactor.trimToRecent(original, 10);
		assertEquals(3, trimmed.size());
	}

	@Test
	void trimKeepsCompactedSummaryInDroppedRegion() {
		List<LlmClient.Message> history = new ArrayList<>();
		history.add(LlmClient.Message.user("<compacted-summary>\nold memory\n</compacted-summary>"));
		history.addAll(messages(7));
		List<LlmClient.Message> trimmed = HistoryCompactor.trimToRecent(history, 3);
		assertEquals(4, trimmed.size());
		assertTrue(trimmed.get(0).text().startsWith("<compacted-summary>"));
		assertEquals("message-6", trimmed.get(trimmed.size() - 1).text());
	}

	@Test
	void applyCompactsOldRegionIntoSummary() {
		List<LlmClient.Message> history = messages(6);
		HistoryCompactor.Outcome outcome =
				HistoryCompactor.apply(history, 2, 200L, "short summary");
		assertTrue(outcome.compacted());
		assertEquals(4, outcome.dropped());
		assertEquals(3, history.size());
		assertTrue(history.get(0).text().startsWith("<compacted-summary>"));
		assertEquals("message-4", history.get(1).text());
		assertEquals("message-5", history.get(2).text());
		// 压缩后的摘要不会被下一次提问的最近 n 条裁剪丢掉
		List<LlmClient.Message> afterNextAsk = HistoryCompactor.trimToRecent(history, 2);
		assertEquals(3, afterNextAsk.size());
		assertTrue(afterNextAsk.get(0).text().startsWith("<compacted-summary>"));
	}

	@Test
	void applyFallsBackToTrimWhenSummaryIsNotShorter() {
		List<LlmClient.Message> history = messages(6);
		HistoryCompactor.Outcome outcome =
				HistoryCompactor.apply(history, 2, 20L, "not-short-enough-summary");
		assertFalse(outcome.compacted());
		assertEquals(4, outcome.dropped());
		assertEquals(2, history.size());
		assertFalse(history.get(0).text().contains("<compacted-summary>"));
	}

	@Test
	void applyFallsBackToTrimWhenSummaryNull() {
		List<LlmClient.Message> history = messages(6);
		HistoryCompactor.Outcome outcome = HistoryCompactor.apply(history, 2, 200L, null);
		assertFalse(outcome.compacted());
		assertEquals(4, outcome.dropped());
		assertEquals(2, history.size());
	}
}
