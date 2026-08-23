package com.swaydy.opencraft.agent.skills;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Skill} 的纯 Java 单测：SKILL.md frontmatter 解析
 * （合法文档/缺字段/非法 name/列表字段/正文裁剪）与按工具过滤。
 */
class SkillTest {

	private static final String VALID = """
			---
			name: dig-down-staircase
			description: How to descend underground by digging a staircase.
			requires_tools: player_mine, player_goto
			---

			# Staircase Digging

			Never dig a 1x1 shaft straight below while standing beside it.
			""";

	@Test
	void parsesValidFrontmatter() {
		Skill skill = Skill.parse(VALID);
		assertNotNull(skill);
		assertEquals("dig-down-staircase", skill.name());
		assertEquals("How to descend underground by digging a staircase.", skill.description());
		assertEquals(java.util.List.of("player_mine", "player_goto"), skill.requiresTools());
		assertTrue(skill.body().startsWith("# Staircase Digging"));
		assertTrue(skill.body().endsWith("beside it."), "正文应去首尾空白: " + skill.body());
	}

	@Test
	void parsesBracketedListAndCRLF() {
		String crlf = VALID.replace("\n", "\r\n").replace("player_mine, player_goto",
				"[player_mine, player_goto]");
		Skill skill = Skill.parse(crlf);
		assertNotNull(skill, "CRLF 与 [a, b] 列表写法都应支持");
		assertEquals(java.util.List.of("player_mine", "player_goto"), skill.requiresTools());
	}

	@Test
	void rejectsMissingFields() {
		assertNull(Skill.parse(null));
		assertNull(Skill.parse("   "));
		assertNull(Skill.parse("no frontmatter at all"));
		assertNull(Skill.parse("---\nname: broken\n---\nbody"), "缺 description 拒绝");
		assertNull(Skill.parse("---\ndescription: no name\n---\nbody"), "缺 name 拒绝");
		assertNull(Skill.parse("---\nname: x\ndescription: \n---\nbody"), "空 description 拒绝");
		assertNull(Skill.parse("---\nname: x\ndescription: unclosed"), "frontmatter 未闭合拒绝");
	}

	@Test
	void rejectsInvalidNameShape() {
		assertNull(Skill.parse("---\nname: BadName\ndescription: d\n---\nb"), "大写拒绝");
		assertNull(Skill.parse("---\nname: has space\ndescription: d\n---\nb"), "空格拒绝");
		assertNull(Skill.parse("---\nname: -leading\ndescription: d\n---\nb"), "连字符开头拒绝");
	}

	@Test
	void ignoresUnknownFrontmatterKeys() {
		String withExtra = VALID.replace("requires_tools:", "version: 1\nrequires_tools:");
		Skill skill = Skill.parse(withExtra);
		assertNotNull(skill, "保留字段之外的键应忽略（向前兼容）");
		assertEquals(java.util.List.of("player_mine", "player_goto"), skill.requiresTools());
	}

	@Test
	void filtersByAvailableTools() {
		Skill skill = Skill.parse(VALID);
		assertNotNull(skill);
		assertTrue(skill.applicableTo(Set.of("player_mine", "player_goto", "player_find")));
		assertFalse(skill.applicableTo(Set.of("player_mine")), "缺任一依赖工具即不适用");
		assertFalse(skill.applicableTo(null));
		// 无 requires_tools = 不限工具（纯聊天预设也能用）
		Skill universal = Skill.parse("---\nname: no-tools\ndescription: d\n---\nbody");
		assertNotNull(universal);
		assertTrue(universal.applicableTo(Set.of()));
	}
}
