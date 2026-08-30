package com.swaydy.opencraft.loop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 循环事件定义的静态注册表（在 {@code LoopModule.init()} 时注册内置定义）。
 *
 * <p>纯 Java、无 Minecraft 依赖,可 JUnit 单测。重复 id 注册时告警并忽略后注册的
 * （保留先注册者）。
 */
public final class LoopRegistry {
	private static final Map<String, LoopDefinition> DEFINITIONS = new LinkedHashMap<>();

	private LoopRegistry() {
	}

	/** 注册一个循环事件定义（重复 id 告警并忽略,保留先注册的）。 */
	public static void register(LoopDefinition def) {
		if (def == null || def.id() == null) {
			return;
		}
		if (DEFINITIONS.containsKey(def.id())) {
			com.swaydy.opencraft.OpenCraftMod.LOGGER.warn(
					"[OpenCraft] 循环事件 {} 重复注册,已忽略", def.id());
			return;
		}
		DEFINITIONS.put(def.id(), def);
	}

	/**
	 * 注册一个内置循环事件预设（{@code loop/presets/} 包,实现 {@code LoopPreset} SPI）：
	 * 用其 {@code definition()} 组装定义后注册。重复 id 告警忽略。
	 */
	public static void register(com.swaydy.opencraft.loop.presets.LoopPreset preset) {
		if (preset == null) {
			return;
		}
		register(preset.definition());
	}

	/** 按 id 取定义;未注册返回 null。 */
	public static LoopDefinition def(String id) {
		return DEFINITIONS.get(id);
	}

	/** 全部已注册定义（注册顺序,供状态输出）。 */
	public static List<LoopDefinition> all() {
		return new ArrayList<>(DEFINITIONS.values());
	}
}