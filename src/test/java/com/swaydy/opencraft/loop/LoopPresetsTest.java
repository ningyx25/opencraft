package com.swaydy.opencraft.loop;

import com.swaydy.opencraft.loop.presets.BreathAuraLoop;
import com.swaydy.opencraft.loop.presets.ExtinguishLoop;
import com.swaydy.opencraft.loop.presets.FeedAuraLoop;
import com.swaydy.opencraft.loop.presets.HealAuraLoop;
import com.swaydy.opencraft.loop.presets.LoopPreset;
import com.swaydy.opencraft.loop.presets.PickupAuraLoop;
import com.swaydy.opencraft.loop.presets.RepelMonstersLoop;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置循环事件预设（{@code loop/presets/}）的纯 Java 单测：{@code definition()} 组装校验
 * （id / 三组成部分 / 运行参数 / persistent 守护语义）与 {@link LoopRegistry} 注册。
 *
 * <p>不依赖 Minecraft 运行时：预设的 Minecraft 访问都在方法体/闭包体内（懒解析）,
 * 组装与注册只触达纯 Java 的 {@link LoopDefinition} / {@link LoopRegistry}。
 */
class LoopPresetsTest {

	/** 全部内置预设（与 {@code LoopModule.init()} 的注册清单一致）。 */
	private static List<LoopPreset> allBuiltins() {
		return List.of(new HealAuraLoop(), new FeedAuraLoop(), new BreathAuraLoop(),
				new ExtinguishLoop(), new PickupAuraLoop(), new RepelMonstersLoop());
	}

	// ------------------------------------------------------------------
	// definition() 组装校验
	// ------------------------------------------------------------------

	@Test
	void everyBuiltinBuildsValidDefinition() {
		for (LoopPreset preset : allBuiltins()) {
			String name = preset.getClass().getSimpleName();
			LoopDefinition def = assertDoesNotThrow(preset::definition,
					name + ".definition() 组装不应抛异常");
			assertNotNull(def.id(), name + " 应有 id");
			assertFalse(def.id().isBlank(), name + " 的 id 不应为空");
			assertEquals(def.id().toLowerCase(), def.id(), name + " 的 id 应为小写");
			assertNotNull(def.trigger(), name + " 应有触发条件");
			assertNotNull(def.event(), name + " 应有执行事件");
			assertNotNull(def.monitor(), name + " 应有监测函数");
			assertTrue(def.intervalTicks() >= 1, name + " 的 intervalTicks 应 ≥ 1");
			assertEquals(0, def.maxIterations(), name + " 守护型预设不应限制迭代");
			assertTrue(def.persistent(), name + " 内置守护型循环应 persistent");
			assertNotNull(def.displayName(), name + " 应有显示名（配置界面卡片用）");
			assertFalse(def.displayName().isBlank(), name + " 的显示名不应为空");
			assertNotNull(def.description(), name + " 应有说明（配置界面卡片用）");
			assertFalse(def.description().isBlank(), name + " 的说明不应为空");
		}
	}

	@Test
	void builtinIdsAreUnique() {
		Set<String> ids = new HashSet<>();
		for (LoopPreset preset : allBuiltins()) {
			assertTrue(ids.add(preset.id()),
					"内置循环事件 id 重复: " + preset.id());
		}
	}

	@Test
	void healAuraKeepsDocumentedParameters() {
		LoopDefinition def = new HealAuraLoop().definition();
		assertEquals("heal_aura", def.id());
		assertEquals(40, def.intervalTicks());
		assertTrue(def.persistent());
	}

	// ------------------------------------------------------------------
	// 注册表
	// ------------------------------------------------------------------

	@Test
	void registryRegistersAllBuiltins() {
		for (LoopPreset preset : allBuiltins()) {
			LoopRegistry.register(preset);
		}
		for (LoopPreset preset : allBuiltins()) {
			LoopDefinition def = LoopRegistry.def(preset.id());
			assertNotNull(def, "注册后应能按 id 取到 " + preset.id());
			assertEquals(preset.definition(), def, "注册的应是预设组装的定义");
		}
	}

	@Test
	void registryIgnoresDuplicateRegistration() {
		LoopRegistry.register(new HealAuraLoop());
		int before = (int) LoopRegistry.all().stream()
				.filter(def -> def.id().equals(HealAuraLoop.ID)).count();
		LoopRegistry.register(new HealAuraLoop());
		int after = (int) LoopRegistry.all().stream()
				.filter(def -> def.id().equals(HealAuraLoop.ID)).count();
		assertEquals(before, after, "重复注册应被忽略");
	}
}
