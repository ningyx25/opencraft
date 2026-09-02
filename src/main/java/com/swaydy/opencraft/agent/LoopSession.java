package com.swaydy.opencraft.agent;

import com.swaydy.opencraft.agent.hooks.LoopHook;
import com.swaydy.opencraft.agent.hooks.LoopHooks;
import com.swaydy.opencraft.ai.AiBlockConfig;
import com.swaydy.opencraft.ai.LlmClient;
import com.swaydy.opencraft.assistant.AiAssistant;
import com.swaydy.opencraft.assistant.player.AiAssistantPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 单次提问（一次 agentic loop 任务）的共享状态：跨轮传递消息列表、忙锁、计划、暂停标记，
 * 以及装配在本次任务上的 {@link LoopHook} 列表（参考 deepseek-harness：每个 agent 有自己的
 * scoped 上下文，守卫/工具等横切能力以监听器形式挂载、各自持有 per-task 状态）。
 *
 * <p>本类是纯内部的可变状态载体（字段公开以便同包与 {@code agent.hooks} 子包的钩子读写），
 * 不是公共 API。loop 的<b>机制</b>（调度、流式、续轮、暂停/恢复）留在 {@link AgentRuntime}，
 * loop 的<b>策略</b>（重复/停滞/终止守卫、task_plan、ask_player）由 {@link #hooks} 承载。
 */
public final class LoopSession {
	/** 提问的主人玩家（真 ServerPlayer）。 */
	public final ServerPlayer player;
	/** 执行任务的 AI 助手（真玩家 bot）。 */
	public final AiAssistant assistant;
	/** 生效的方块配置（接口/模型/温度/记忆条数等）。 */
	public final AiBlockConfig config;
	/** 本次解析出的 Agent 预设（插件工具 + persona + 最大轮数）。 */
	public final AgentDefinition agent;
	/** 喂给 LLM 的消息列表（system 独立于 {@link LlmClient.Request}，不在此列表内）。 */
	public final List<LlmClient.Message> messages;
	/** 忙锁键（助手绑定的方块；与 RUNNING/LIVE/PENDING_* 键一致）。 */
	public final GlobalPos lockKey;
	/** 对话记忆键（null = 不写长期历史，如打招呼）。 */
	public final GlobalPos historyKey;
	/** GUI 模式的方块位置/维度（非 GUI 为 null）。 */
	public final BlockPos guiBlockPos;
	public final ResourceKey<Level> guiDimension;
	/** 是否 GUI 模式（true = 增量/reply 事件，不广播世界聊天）。 */
	public final boolean gui;
	/** 本次提问的世界内浮层会话号（一次提问共用同一会话；客户端只认最新会话）。 */
	public final int sessionId;

	/** 装配在本次任务上的横切钩子（{@link LoopHooks#createDefaults()} 每次任务新建一份）。 */
	public final List<LoopHook> hooks;

	/** 是否已被玩家中断（/opencraft interrupt 或 GUI「中断」按钮）；置位后所有异步回调失效。 */
	public volatile boolean cancelled = false;
	/** 本轮请求的重试计数（跨重试调度保持，最多 {@link LlmRetryPolicy#MAX_RETRIES} 次）。 */
	public final int[] llmRetries = {0};

	/** 本轮请求的 system 文本（每轮在 runRound 重建；总结轮/提问恢复复用，保持上下文一致）。 */
	public String system = null;

	/** 模型通过 task_plan 维护的当前任务计划（格式化文本），null = 无计划；注入 system。 */
	public String planText = null;
	/** 解析后的任务计划（终止守卫判断「是否还有未完成步骤」）；null = 无计划。 */
	public TaskPlan plan = null;
	/** 本轮是否成功更新过任务计划（停滞守卫据此判定「做了实事」；每轮 dispatch 前由 runtime 重置）。 */
	public boolean planUpdatedThisRound = false;
	/** 终止守卫已暂缓收尾的次数（上限 {@link TaskCompletionGuard#MAX_HOLDS}）。 */
	public int terminalHolds = 0;

	/** ask_player 已暂停等待玩家回答（本批工具停止继续，等 answer/超时 恢复）。 */
	public boolean pausedByAsk = false;
	/** 延迟动作（goto/mine/place/container_open）已注册，循环暂停等待 [Event] 事件恢复。 */
	public boolean pausedByAction = false;

	public LoopSession(ServerPlayer player, AiAssistant assistant, AiBlockConfig config,
	                   AgentDefinition agent, List<LlmClient.Message> messages,
	                   GlobalPos lockKey, GlobalPos historyKey,
	                   BlockPos guiBlockPos, ResourceKey<Level> guiDimension, boolean gui,
	                   int sessionId) {
		this.player = player;
		this.assistant = assistant;
		this.config = config;
		this.agent = agent;
		this.messages = messages;
		this.lockKey = lockKey;
		this.historyKey = historyKey;
		this.guiBlockPos = guiBlockPos;
		this.guiDimension = guiDimension;
		this.gui = gui;
		this.sessionId = sessionId;
		this.hooks = LoopHooks.createDefaults();
	}

	/**
	 * 玩家形态助手是否有异步动作在途：手动移动进行中（manual=true 且有目标）或挖掘进行中。
	 * 终止守卫与延迟动作机制共用。只认手动指令——跟随逻辑产生的移动任务动作，不应阻止收尾。
	 */
	public boolean asyncActionInFlight() {
		return asyncActionInFlight(assistant);
	}

	/** 静态版：供暂无 session 的收尾路径（endTask）判定。 */
	public static boolean asyncActionInFlight(AiAssistant assistant) {
		if (assistant instanceof AiAssistantPlayer p && p.movement() != null) {
			return (p.movement().isMoving() && p.movement().isManual())
					|| p.movement().isMining();
		}
		return false;
	}
}
