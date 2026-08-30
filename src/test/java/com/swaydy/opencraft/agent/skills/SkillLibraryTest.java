package com.swaydy.opencraft.agent.skills;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkillLibrary} 的单测：遍历 skills/index.json 校验每个登记的内置技能
 * 都能从 classpath 加载且形状合法（通用断言——新增技能自动纳入校验）;
 * promptsFragment 按「预设绑定 + 可用工具」双重过滤渲染。
 */
class SkillLibraryTest {

	/** general_agent 预设的全部工具（渲染用）。 */
	private static final Set<String> AGENT_TOOLS = Set.of(
			"teleport_to_player", "player_goto", "player_stop", "player_teleport", "player_jump",
			"player_find", "player_mine", "player_place", "player_craft",
			"player_item_move", "player_hotbar_select", "player_hand_to_player",
			"player_inventory",
			"player_container_open", "player_container_list", "player_container_take",
			"player_container_put", "player_container_close",
			"ask_player", "task_plan");

	/** 已知工具名全集（requires_tools 合法性校验用）。 */
	private static final Set<String> KNOWN_TOOLS = Set.of(
			"teleport_to_player", "player_goto", "player_stop", "player_teleport", "player_jump",
			"player_find", "player_mine", "player_place", "player_craft",
			"player_inventory", "player_item_move", "player_hotbar_select",
			"player_hand_to_player",
			"player_container_open", "player_container_list", "player_container_take",
			"player_container_put", "player_container_close",
			"ask_player", "task_plan");

	@Test
	void everyIndexedSkillLoadsAndIsValid() throws Exception {
		List<Skill> skills = SkillLibrary.load(SkillLibraryTest.class.getClassLoader());
		assertFalse(skills.isEmpty(), "内置技能应随主资源一起打进 jar/测试 classpath");
		// index.json 里登记的每一个名字都必须加载成功（缺一个即测试失败）
		JsonArray index;
		try (var in = SkillLibraryTest.class.getClassLoader()
				.getResourceAsStream(SkillLibrary.INDEX_RESOURCE)) {
			assertNotNull(in, "skills/index.json 应存在于 classpath");
			index = JsonParser.parseString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
					.getAsJsonArray();
		}
		assertEquals(index.size(), skills.size(),
				"index.json 条目数 = 实际加载数（有条目被跳过即失败）");
		for (Skill s : skills) {
			assertFalse(s.description().isBlank(), s.name() + " 的 description 非空");
			assertTrue(KNOWN_TOOLS.containsAll(s.requiresTools()),
					s.name() + " 的 requires_tools 引用了未知工具: " + s.requiresTools());
			assertFalse(s.body().isBlank(), s.name() + " 的正文非空");
		}
	}

	@Test
	void woodSkillKeepsCoreContent() {
		// 核心内容抽查（砍树流程:先 find 再自下而上挖 + 按 picked-up 计数）
		Skill wood = SkillLibrary.builtIns().stream()
				.filter(s -> s.name().equals("gather-wood"))
				.findFirst().orElse(null);
		assertNotNull(wood, "gather-wood 应在 index.json 里并被加载");
		assertTrue(wood.body().contains("player_find"));
		assertTrue(wood.body().contains("BOTTOM up"), "正文应说明自下而上挖树干");
	}

	@Test
	void boundSkillsRenderForAgentToolSet() {
		// general_agent 绑定 2 个技能 + 工具齐备 → 2 个小节全部渲染
		String fragment = SkillLibrary.promptsFragment(
				List.of("gather-wood", "craft-toolchain"),
				AGENT_TOOLS);
		assertTrue(fragment.startsWith("# Skills"));
		for (String name : new String[]{"gather-wood", "craft-toolchain"}) {
			assertTrue(fragment.contains("## " + name), "应渲染技能小节: " + name);
		}
	}

	@Test
	void generalAgentBoundSkillsAllRenderForAgentTools() {
		// 与 GeneralAgent.skills() 的绑定一一对应（新增/改名技能时两边同步修改）：
		// 守护「绑定名都在库里且目录名一致 + requires_tools 全部命中 general_agent 真实工具集」
		// ——绑定名拼错或工具名写错时该技能会静默不注入，这里让它在单测里暴露。
		List<String> bound = List.of("gather-wood", "craft-toolchain", "mine-ores",
				"smelt-in-furnace", "chest-storage", "build-basics");
		String fragment = SkillLibrary.promptsFragment(bound, AGENT_TOOLS);
		assertTrue(fragment.startsWith("# Skills"), "应渲染出 # Skills 大节");
		for (String name : bound) {
			assertTrue(fragment.contains("## " + name),
					"general_agent 绑定的技能应对其工具集渲染: " + name);
		}
	}

	@Test
	void unboundSkillNeverInjects() {
		// 绑定管理:没绑定（空列表/null/绑别的）一律不注入——不再全量加载
		assertEquals("", SkillLibrary.promptsFragment(List.of(), AGENT_TOOLS), "空绑定列表不注入");
		assertEquals("", SkillLibrary.promptsFragment(null, AGENT_TOOLS), "null 绑定不注入");
		assertEquals("", SkillLibrary.promptsFragment(List.of("some-other-skill"), AGENT_TOOLS),
				"未绑定的技能不注入");
	}

	@Test
	void boundSkillStillFilteredByRequiredTools() {
		// 纯聊天工具集（缺全部动作工具）→ 所有绑定的技能都不渲染
		assertEquals("", SkillLibrary.promptsFragment(
				List.of("gather-wood", "craft-toolchain"),
				Set.of("ask_player", "task_plan")));
		// 绑定了不存在的名字（拼写错误）→ 静默忽略为空（AgentRegistry.init 有一次性告警）
		assertEquals("", SkillLibrary.promptsFragment(List.of("typo-name"), AGENT_TOOLS));
	}

	@Test
	void missingIndexYieldsEmptyLibrary() {
		// 用空的 classloader 模拟索引缺失:不抛异常,返回空表（技能是增强不是依赖）
		ClassLoader empty = new ClassLoader(null) {
		};
		List<Skill> skills = SkillLibrary.load(empty);
		assertTrue(skills.isEmpty(), "索引缺失应为空库而非异常");
		// 空库（注入库,绕开静态缓存）+ 任意绑定 → 片段为空
		assertEquals("", SkillLibrary.render(List.of(), List.of("gather-wood"), AGENT_TOOLS));
	}
}
