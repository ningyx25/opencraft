package com.swaydy.opencraft.e2e;

import com.swaydy.opencraft.OpenCraftMod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 端到端测试任务的静态注册表（在 {@code OpenCraftMod.onInitialize} 时初始化）。
 *
 * <p>与 {@link com.swaydy.opencraft.agent.AgentRegistry} /
 * {@link com.swaydy.opencraft.loop.LoopRegistry} 同一套管理思路：内置任务。
 * 注册后可通过 {@link #byId(String)} 或 {@link #all()} 查询。</p>
 */
public final class E2ERegistry {
	private static final Map<String, E2ETask> TASKS = new LinkedHashMap<>();
	private static boolean initialized = false;

	private E2ERegistry() {
	}

	/** 注册内置端到端任务（幂等，只有首次调用时注册）。 */
	public static void init() {
		if (initialized) {
			return;
		}
		initialized = true;
		register(new com.swaydy.opencraft.e2e.tasks.ChopTreeTask());
		register(new com.swaydy.opencraft.e2e.tasks.PlaceWorkbenchTask());
		register(new com.swaydy.opencraft.e2e.tasks.CraftWoodenPickaxeTask());
		register(new com.swaydy.opencraft.e2e.tasks.CraftStonePickaxeTask());
		register(new com.swaydy.opencraft.e2e.tasks.MineStoneTask());
		register(new com.swaydy.opencraft.e2e.tasks.StoreItemsInChestTask());
		register(new com.swaydy.opencraft.e2e.tasks.RetrieveFromChestTask());
		register(new com.swaydy.opencraft.e2e.tasks.OrganizeContainerTask());
		register(new com.swaydy.opencraft.e2e.tasks.SmeltOreInFurnaceTask());
		register(new com.swaydy.opencraft.e2e.tasks.AddFuelAndOreToFurnaceTask());
		OpenCraftMod.LOGGER.info("[OpenCraft] 端到端测试模块已注册 {} 个内置任务", TASKS.size());
	}

	/** 注册一个任务（重复 id 告警忽略）。 */
	public static void register(E2ETask task) {
		if (task == null || task.id() == null) {
			return;
		}
		if (TASKS.containsKey(task.id())) {
			OpenCraftMod.LOGGER.warn("[OpenCraft] E2E 任务 {} 重复注册，已忽略", task.id());
			return;
		}
		TASKS.put(task.id(), task);
	}

	/** 按 id 取任务；未注册返回 null。 */
	public static E2ETask byId(String id) {
		return TASKS.get(id);
	}

	/** 全部已注册任务（注册顺序，供 /opencraft e2e list 输出）。 */
	public static List<E2ETask> all() {
		return new ArrayList<>(TASKS.values());
	}
}