package com.swaydy.opencraft.agent.skills;

import com.swaydy.opencraft.agent.AgentDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentDefinition} 的 skills 绑定字段单测（空构造不触碰插件/Minecraft 类）：
 * null 归一为空表、旧五参构造器兼容（默认不绑定）。
 */
class AgentDefinitionSkillsTest {

	@Test
	void nullSkillsNormalizedToEmpty() {
		AgentDefinition def = new AgentDefinition("test_agent", "test", null, "persona", 5, null);
		assertTrue(def.skills().isEmpty(), "null 绑定列表应归一为空表");
	}

	@Test
	void legacyConstructorBindsNothing() {
		// 旧五参构造器（ChatAgent 等纯聊天预设沿用）：默认不绑定任何技能
		AgentDefinition def = new AgentDefinition("test_agent", "test", null, "persona", 5);
		assertTrue(def.skills().isEmpty());
	}

	@Test
	void boundListIsCopied() {
		java.util.ArrayList<String> mutable = new java.util.ArrayList<>(List.of("gather-wood"));
		AgentDefinition def = new AgentDefinition("test_agent", "test", null, "persona", 5, mutable);
		mutable.add("later-mutation");
		assertEquals(List.of("gather-wood"), def.skills(), "绑定列表应防御性拷贝");
	}
}
