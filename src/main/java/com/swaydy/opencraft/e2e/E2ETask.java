package com.swaydy.opencraft.e2e;

/**
 * 端到端测试任务 SPI（内置任务集中在 {@code e2e/tasks/} 子包，同
 * {@code agent/presets/BaseAgent} / {@code loop/presets/LoopPreset} 的管理思路）。
 *
 * <p>每个任务 = 一个真实世界的场景：harness 负责摆配置方块、召唤玩家形态助手、
 * 下发任务指令、等待 agentic loop 完成并清理；任务本身只声明
 * 「场景准备」「验证条件」「场景还原」三件事。验证基于世界方块状态与助手背包
 * 物品计数（真实结果，不是文本匹配）。</p>
 *
 * <p>执行线程约定：{@link #setup}/{@link #verify}/{@link #teardown} 都在<b>服务端线程</b>
 * 调用（可直接读写 ServerLevel / 实体）；{@link #taskPrompt()} 是下发给真实 LLM 的任务指令。</p>
 */
public interface E2ETask {

	/** 任务唯一 id（小写 kebab-case，如 "chop_tree"）。 */
	String id();

	/** 一句话描述（/opencraft e2e list 输出用）。 */
	String description();

	/** 下发给助手（general_agent + 真实 LLM）的任务指令。 */
	String taskPrompt();

	/** 单任务超时（默认 4 分钟；真实 LLM agentic loop 需要充足时间）。 */
	default long timeoutMillis() {
		return 4 * 60_000L;
	}

	/**
	 * 场景准备（服务端线程）：种树/清场等。默认空操作——
	 * harness 已统一铺好石质平台、放置配置方块。
	 */
	default void setup(E2EContext ctx) {
	}

	/**
	 * 验证条件（服务端线程，agentic loop 完成后调用）：检查世界/背包的真实状态。
	 * 返回 true = 任务通过。
	 */
	boolean verify(E2EContext ctx);

	/** 场景还原（服务端线程，可选）：清除任务种下的树/放置的方块，供下一任务复用区域。 */
	default void teardown(E2EContext ctx) {
	}
}
