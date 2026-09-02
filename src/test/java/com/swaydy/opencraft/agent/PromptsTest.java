package com.swaydy.opencraft.agent;

import com.swaydy.opencraft.ai.AiBlockConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prompts 提示词组装与 KV 前缀缓存（Prompt Caching）友好特性的纯 Java 单测：
 * 验证 staticSystem 的确定性、不变性，以及动态上下文的解耦。
 */
class PromptsTest {

	@Test
	void staticSystemIsDeterministicAndCacheFriendly() {
		AiBlockConfig config = new AiBlockConfig();
		AgentDefinition agent = new AgentDefinition("general", "General Agent", List.of(),
				"You are a helpful companion bot.", 250, List.of("gather-wood", "craft-toolchain"));

		String s1 = Prompts.staticSystem(config, agent);
		String s2 = Prompts.staticSystem(config, agent);

		assertNotNull(s1);
		assertEquals(s1, s2, "staticSystem 必须在多次调用间完全一致以保证 KV 前缀缓存命中");

		// 验证静态头部包含必要结构
		assertTrue(s1.contains("# Identity"), "必须包含 # Identity");
		assertTrue(s1.contains("Your name is"), "必须包含名字指令");
		assertTrue(s1.contains("You are a helpful companion bot."), "必须包含 personaPrompt");

		// 验证不包含动态状态（防止缓存击穿）
		assertFalse(s1.contains("# Game Context"), "staticSystem 严禁包含动态 # Game Context");
		assertFalse(s1.contains("Player State"), "staticSystem 严禁包含动态 Player State");
		assertFalse(s1.contains("Assistant State"), "staticSystem 严禁包含动态 Assistant State");
		assertFalse(s1.contains("# Current Task Plan"), "staticSystem 严禁包含动态任务计划");
	}

	@Test
	void staticSystemHandlesNullAgentGracefully() {
		AiBlockConfig config = new AiBlockConfig();
		String s = Prompts.staticSystem(config, null);
		assertNotNull(s);
		assertTrue(s.contains("# Identity"));
	}

	@Test
	void multiTurnPrefixRemainsIdenticalForCaching() {
		AiBlockConfig config = new AiBlockConfig();
		AgentDefinition agent = new AgentDefinition("general", "General Agent", List.of(),
				"You are a helpful companion bot.", 250, List.of("gather-wood", "craft-toolchain"));

		String systemRound0 = Prompts.staticSystem(config, agent);
		String systemRound1 = Prompts.staticSystem(config, agent);
		String systemRound10 = Prompts.staticSystem(config, agent);

		// 验证跨轮次 system 绝对不变
		assertEquals(systemRound0, systemRound1);
		assertEquals(systemRound1, systemRound10);
	}
}
