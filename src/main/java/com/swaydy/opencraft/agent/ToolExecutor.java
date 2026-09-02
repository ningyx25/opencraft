package com.swaydy.opencraft.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.swaydy.opencraft.OpenCraftMod;
import com.swaydy.opencraft.agent.hooks.AskPlayerHook;
import com.swaydy.opencraft.agent.hooks.BatchClaim;
import com.swaydy.opencraft.agent.hooks.LoopHook;
import com.swaydy.opencraft.agent.hooks.TaskPlanHook;
import com.swaydy.opencraft.agent.hooks.ToolExec;
import com.swaydy.opencraft.agent.hooks.ToolHandle;
import com.swaydy.opencraft.ai.LlmClient;
import com.swaydy.opencraft.assistant.AiAssistant;
import com.swaydy.opencraft.plugins.ToolContext;
import com.swaydy.opencraft.plugins.ToolDefinition;
import com.swaydy.opencraft.plugins.ToolResult;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具执行管线（guarded tool execution pipeline）——对齐 deepseek-harness 的 {@code tools/}
 * 包：「工具注册表 + 受守卫的执行管线，loop 通过它分派工具」。
 *
 * <p>本类是 loop 驱动里<b>唯一</b>的工具分派点，只负责「跑工具」这一机制；横切策略都在
 * {@link LoopHook} 监听器里，真正的 loop 协调动作（暂停等玩家回答、注册延迟动作、移动控制器
 * 状态查询、观察者回调）通过 {@link Host} 回调交给 {@link AgentRuntime}：
 * <ol>
 *   <li>{@link LoopHook#beforeBatch}：ask_player「确认优先」整批短路（有效提问只处理它并暂停）；</li>
 *   <li>逐 call：{@link LoopHook#handleTool} 认领核心工具（task_plan/ask_player），否则查插件工具
 *       注册表（{@link ToolDefinition}）；未知工具 / 参数错误 / 执行异常 / 冗余 goto / 延迟动作互斥
 *       在此处理（冗余 goto 与互斥策略经 {@link Host} 查询，保持本类对具体工具名无感）；</li>
 *   <li>每个结果过 {@link LoopHook#afterTool}（重复调用守卫撞阈值追加提醒）；</li>
 *   <li>整批结束过 {@link LoopHook#afterBatch}（停滞守卫）；</li>
 *   <li>deferred 工具经 {@link Host#registerPendingAction} 注册，由 loop 暂停等 [Event] 续轮。</li>
 * </ol>
 * 结果统一以 [工具名 成功/失败] 开头并裁剪超长文本；每批最多 {@link #MAX_TOOLS_PER_ROUND} 个。
 */
final class ToolExecutor {
	/** 单批最多执行的工具调用数（超出部分直接以错误结果返回，防止一次连发太多调用撑爆上下文）。 */
	static final int MAX_TOOLS_PER_ROUND = 6;

	private ToolExecutor() {
	}

	/**
	 * loop 协调动作：执行管线需要 loop（{@link AgentRuntime}）承载的、超出「跑工具」本身的副作用。
	 * 把这些放在回调里，让本类保持为通用管线、不直接持有 ask/延迟动作的状态机。
	 */
	interface Host {
		/** ask_player 有效提问：暂停循环等玩家回答（loop 的 ask 暂停/恢复状态机）。 */
		void pauseForAnswer(LoopSession ctx, String question, int nextRound);

		/** deferred 工具：注册延迟动作（移动/挖掘），loop 暂停等待 [Event] 事件续轮。 */
		void registerPendingAction(LoopSession ctx, String toolName, JsonObject args, int round);

		/** player_goto 是否与在途手动移动同目标（等待到达的再确认）——冗余 goto 豁免重复执行。 */
		boolean isRedundantInFlightGoto(AiAssistant assistant, String toolName, JsonObject args);

		/** 该工具是否为互斥的延迟动作（goto/mine/place/container_open 共用一个移动控制器）。 */
		boolean isAsyncActionTool(String toolName);

		/** 工具执行后通知观察者（e2e 评测日志等）。 */
		void notifyToolExecuted(String toolName, ToolResult result);
	}

	/**
	 * 在服务端线程分派一批工具调用，返回要追加到消息列表的 tool/reminder 消息。
	 * ask 暂停与延迟动作注册通过 {@link Host} 副作用完成（调用方随后检查 session 的暂停标记）。
	 */
	static List<LlmClient.Message> executeBatch(LoopSession ctx,
	                                           List<LlmClient.ToolCallBlock> calls, int round,
	                                           Host host) {
		List<LlmClient.Message> results = new ArrayList<>();
		Map<String, ToolDefinition> tools = ctx.agent.toolMap();
		ctx.planUpdatedThisRound = false;
		List<String> executedNames = new ArrayList<>();
		int executed = 0;
		// 本批已启动的延迟动作（一次只允许一个：goto/mine/place/container_open 共用一个移动控制器）
		String deferredTool = null;
		JsonObject deferredArgs = null;
		// beforeBatch 已认领的 call id（无效 ask_player：已回错误，逐 call 分派时跳过、不重复处理）
		Set<String> claimedIds = new HashSet<>();

		// 1) beforeBatch：ask_player 有效提问短路（只处理它并暂停，跳过同批其余工具）
		for (LoopHook hook : ctx.hooks) {
			BatchClaim claim = hook.beforeBatch(ctx, calls);
			if (!claim.claimed()) {
				continue;
			}
			ToolHandle handle = claim.handle();
			String claimName = claim.call().name() == null ? "" : claim.call().name().trim();
			results.add(toolResultMessage(claim.call().id(), claimName, handle.result()));
			executedNames.add(claimName);
			com.swaydy.opencraft.logging.DebugLog.log("tool",
					"助手执行工具 {} → {}", claimName, handle.result().message());
			host.notifyToolExecuted(claimName, handle.result());
			if (handle.pausesForAnswer()) {
				host.pauseForAnswer(ctx, handle.askQuestion(), round + 1);
				return results; // 有效提问：暂停等玩家回答，同批其余工具不执行
			}
			claimedIds.add(claim.call().id()); // 无效提问：已回错误，跳过该 call
			break;
		}

		// 2) 逐 call 分派：核心工具钩子认领 → 否则插件工具注册表
		for (LlmClient.ToolCallBlock call : calls) {
			if (claimedIds.contains(call.id())) {
				continue; // beforeBatch 已处理（无效 ask_player 已回错误）
			}
			String toolName = call.name() == null ? "" : call.name().trim();
			if (executed >= MAX_TOOLS_PER_ROUND) {
				com.swaydy.opencraft.logging.DebugLog.log("tool",
						"本轮工具调用达上限（{}），截断后续调用：{}", MAX_TOOLS_PER_ROUND, toolName);
				results.add(LlmClient.Message.toolResult(call.id(), ToolResultPruner.toModelText(toolName, false,
						"You have reached the per-round tool call limit (" + MAX_TOOLS_PER_ROUND + "). "
								+ "Observe the results above before continuing — don't fire off too many calls at once."), true));
				continue;
			}

			ToolResult result;
			boolean countForRepeat = true;

			// 2a) 核心工具（task_plan / 防御性 ask_player）由钩子认领
			ToolHandle handle = ToolHandle.notHandled();
			for (LoopHook hook : ctx.hooks) {
				ToolHandle r = hook.handleTool(ctx, call);
				if (r.isHandled()) {
					handle = r;
					break;
				}
			}
			if (handle.isHandled()) {
				result = handle.result();
				// task_plan 成功=正常更新进度，不计重复链；失败（参数错误）计入，防止错误参数死循环。
				// ask_player 不计（beforeBatch 已处理；到这里仅极端兜底）。
				countForRepeat = TaskPlanHook.TOOL_NAME.equals(toolName) && !result.ok();
				if (AskPlayerHook.TOOL_NAME.equals(toolName)) {
					countForRepeat = false;
				}
				if (handle.pausesForAnswer()) {
					results.add(toolResultMessage(call.id(), toolName, result));
					executed++;
					executedNames.add(toolName);
					host.notifyToolExecuted(toolName, result);
					host.pauseForAnswer(ctx, handle.askQuestion(), round + 1);
					return results;
				}
			} else {
				// 2b) 插件工具注册表分派
				ToolDefinition def = tools.get(toolName);
				if (def == null) {
					result = ToolResult.error("Unknown tool \"" + toolName + "\". Available tools: "
							+ String.join(", ", tools.keySet()));
				} else {
					try {
						JsonObject args = parseArgs(call.arguments());
						if (args == null) {
							result = ToolResult.error("Could not parse the arguments JSON: " + previewArgs(call.arguments())
									+ "; please provide valid argument JSON.");
						} else if (host.isRedundantInFlightGoto(ctx.assistant, toolName, args)) {
							// 重复 goto 豁免：同一目标已在途（等待到达的再确认）——不重复执行、不进重复守卫，
							// 改回一条信息型结果教模型等待走路
							result = ToolResult.ok("Already walking to that target — re-issuing changes nothing. "
									+ "Movement takes a few seconds; use the waiting rounds for other useful steps "
									+ "(e.g. prepare tools) and confirm arrival via the Assistant State.");
							countForRepeat = false;
						} else if (host.isAsyncActionTool(toolName) && deferredTool != null) {
							result = ToolResult.error("Another movement action is already in progress this round; "
									+ "wait for its [Event] outcome before starting another.");
						} else {
							ToolContext toolCtx = new ToolContext(ctx.player.level().getServer(),
									ctx.assistant, ctx.player, (ServerLevel) ctx.player.level());
							result = def.executor().execute(toolCtx, args);
							if (result.deferred()) {
								deferredTool = toolName;
								deferredArgs = args;
							}
						}
					} catch (Exception e) {
						OpenCraftMod.LOGGER.warn("[OpenCraft] 工具 {} 执行异常: {}", toolName, e.toString());
						result = ToolResult.error("Internal error: " + e.getClass().getSimpleName());
					}
				}
			}

			executed++;
			executedNames.add(toolName);
			com.swaydy.opencraft.logging.DebugLog.log("tool",
					"助手执行工具 {} 参数={} → 结果={}", toolName,
					call.arguments() == null ? "{}" : call.arguments(), result.message());
			results.add(toolResultMessage(call.id(), toolName, result));
			OpenCraftMod.LOGGER.info("[OpenCraft] 助手为 {} 执行工具 {} → {}",
					ownerName(ctx), toolName, result.message());
			host.notifyToolExecuted(toolName, result);

			// 3) afterTool 钩子（重复调用守卫：撞阈值追加 user 提醒；冗余 goto / 成功 task_plan 不计）
			ToolExec exec = new ToolExec(call, toolName, result, countForRepeat);
			for (LoopHook hook : ctx.hooks) {
				hook.afterTool(ctx, exec, results);
			}
		}

		// 4) afterBatch 钩子（停滞守卫：连续纯观察无进展追加提醒）。ask 暂停已在上方 return。
		for (LoopHook hook : ctx.hooks) {
			hook.afterBatch(ctx, executedNames, results);
		}

		// 5) 延迟动作注册放在批内全部工具执行完之后：同批的 player_stop 等能正常停掉动作，
		//    注册时若动作已不在途（被后续工具停了）会立即以"被停止"事件恢复，不空等超时
		if (deferredTool != null) {
			host.registerPendingAction(ctx, deferredTool, deferredArgs, round);
		}
		return results;
	}

	/** 组装一条 tool 结果消息：统一 [工具名 成功/失败] 标记开头并裁剪超长文本。 */
	private static LlmClient.Message toolResultMessage(String callId, String toolName, ToolResult result) {
		return LlmClient.Message.toolResult(callId,
				ToolResultPruner.toModelText(toolName, result.ok(), result.message()), !result.ok());
	}

	/**
	 * 解析工具参数字符串；非法 JSON 或非对象时返回 null（工具自行校验缺参；
	 * null 表示"参数格式本身错了"，会把原文回显给模型让它自纠）。
	 */
	private static JsonObject parseArgs(String args) {
		if (args == null || args.isBlank()) {
			return new JsonObject();
		}
		try {
			com.google.gson.JsonElement el = JsonParser.parseString(args);
			return el.isJsonObject() ? el.getAsJsonObject() : null;
		} catch (Exception e) {
			return null;
		}
	}

	private static String previewArgs(String args) {
		if (args == null) {
			return "null";
		}
		String s = args.trim();
		return s.length() <= 120 ? s : s.substring(0, 120) + "…";
	}

	private static String ownerName(LoopSession ctx) {
		return ctx.player == null || ctx.player.getName() == null
				? "?" : ctx.player.getName().getString();
	}
}
