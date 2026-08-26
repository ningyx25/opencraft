package com.swaydy.opencraft.loop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LoopDefinition} 构建器的纯 Java 单测：字段默认值与非法参数校验。
 */
class LoopDefinitionTest {

	private static final LoopCondition TRUE = ctx -> true;
	private static final LoopEvent NOOP = ctx -> { };
	private static final LoopMonitor CONTINUE = ctx -> LoopVerdict.CONTINUE;

	@Test
	void validBuildWithDefaults() {
		LoopDefinition def = LoopDefinition.builder("demo")
				.trigger(TRUE).event(NOOP).monitor(CONTINUE)
				.build();
		assertEquals("demo", def.id());
		assertNull(def.displayName(), "displayName 默认可为 null");
		assertNull(def.description(), "description 默认可为 null");
		assertEquals(20, def.intervalTicks(), "默认间隔应为 20 tick");
		assertEquals(0, def.maxIterations(), "默认不限制迭代次数");
		assertFalse(def.persistent(), "默认一次性循环");
	}

	@Test
	void validBuildWithExplicitFields() {
		LoopDefinition def = LoopDefinition.builder("demo")
				.displayName("演示").description("一个演示循环")
				.trigger(TRUE).event(NOOP).monitor(CONTINUE)
				.intervalTicks(40).maxIterations(10).persistent(true)
				.build();
		assertEquals("演示", def.displayName());
		assertEquals("一个演示循环", def.description());
		assertEquals(40, def.intervalTicks());
		assertEquals(10, def.maxIterations());
		assertTrue(def.persistent());
	}

	@Test
	void rejectsBlankId() {
		assertThrows(IllegalArgumentException.class,
				() -> LoopDefinition.builder("  ").trigger(TRUE).event(NOOP).monitor(CONTINUE).build());
		assertThrows(IllegalArgumentException.class,
				() -> LoopDefinition.builder(null).trigger(TRUE).event(NOOP).monitor(CONTINUE).build());
	}

	@Test
	void rejectsMissingParts() {
		assertThrows(IllegalArgumentException.class,
				() -> LoopDefinition.builder("demo").event(NOOP).monitor(CONTINUE).build(),
				"缺触发条件应拒绝");
		assertThrows(IllegalArgumentException.class,
				() -> LoopDefinition.builder("demo").trigger(TRUE).monitor(CONTINUE).build(),
				"缺执行事件应拒绝");
		assertThrows(IllegalArgumentException.class,
				() -> LoopDefinition.builder("demo").trigger(TRUE).event(NOOP).build(),
				"缺监测函数应拒绝");
	}

	@Test
	void rejectsBadNumbers() {
		assertThrows(IllegalArgumentException.class,
				() -> LoopDefinition.builder("demo").trigger(TRUE).event(NOOP).monitor(CONTINUE)
						.intervalTicks(0).build(),
				"间隔必须 ≥ 1");
		assertThrows(IllegalArgumentException.class,
				() -> LoopDefinition.builder("demo").trigger(TRUE).event(NOOP).monitor(CONTINUE)
						.maxIterations(-1).build(),
				"迭代上限必须 ≥ 0");
	}
}