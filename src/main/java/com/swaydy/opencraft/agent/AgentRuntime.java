package com.swaydy.opencraft.agent;

import com.google.gson.JsonObject;
import com.swaydy.opencraft.OpenCraftMod;
import com.swaydy.opencraft.ai.AiBlockConfig;
import com.swaydy.opencraft.ai.AiCompanionService;
import com.swaydy.opencraft.ai.LlmClient;
import com.swaydy.opencraft.assistant.AiAssistant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Agentic loop 执行器：驱动 AI 助手走“观察 → 决策 → 行动 → 再观察”的循环。
 *
 * <p>设计参照 deepseek-harness 的成熟 Agent 模式：
 * - <b>LLM 请求重试</b>（{@link LlmRetryPolicy}，参考 dsh-llm-retry）：限流/5xx/超时/网络抖动
 *   等瞬时失败按指数退避 + 抖动重试，不把整轮对话变成报错；
 * - <b>重复工具调用守卫</b>（{@link RepeatToolGuard}，参考 dsh-repeat-tool-reminder）：
 *   连续相同工具+相同参数达到阈值时注入提醒（先温和后详细），打断模型的重复死循环；
 * - <b>工具结果标记与裁剪</b>（{@link ToolResultPruner}，参考 dsh-compaction-tool-result-pruner）：
 *   结果统一以 [工具名 成功/失败] 开头，超长结果保头尾裁中间，上下文增长有界；
 * - <b>每轮工具调用上限</b>（参考 dsh-agent-loop 的 maxParallelToolCalls）：防止一次连发太多调用；
 * - <b>历史压缩</b>（参考 dsh-compaction-basic）：历史过长时用一次非工具 LLM 调用把最旧区段
 *   压缩成记忆摘要（&lt;compacted-summary&gt;），比直接裁剪保留更多记忆；压缩失败自动退回裁剪。
 * - <b>向玩家提问（暂停/恢复）</b>（参考 dsh-tool-ask-user）：模型在指令含糊或行动有破坏性/不可逆
 *   影响时调用核心工具 {@code ask_player}，循环暂停并向玩家提问；玩家用 /opencraft answer 回答后
 *   恢复循环；超时（{@link #ASK_TIMEOUT_MS}）未答则按合理假设继续并在回复中说明。
 * - <b>任务计划跟踪</b>（参考 dsh-tool-todo + system-prompt 注入）：模型用核心工具 {@code task_plan}
 *   维护结构化步骤清单（整单替换），每轮循环把当前计划注入 system 上下文，多步任务不丢失进度。
 *
 * <p>线程模型（沿用现有约定）：
 * - HTTP/SSE 读取在工作线程（{@link #EXECUTOR}），流式增量在那边按打字机节奏 reveal；
 * - 工具执行、历史写入、聊天广播一律 {@code server.executeIfPossible} 回服务端线程；
 * - 工具执行完成后由服务端线程把「继续下一轮请求」的任务交回工作线程池；
 * - 长任务（寻路、挖掘）不在工具调用里阻塞——工具只下达指令（设置任务/Goal），立即返回；
 *   模型通过后续 {@code look_around} 观察结果。
 *
 * <p>每轮流程：
 * 1. 组装消息：system（预设 persona + 插件提示词 + 游戏上下文 + 插件上下文）+ 历史 + user；
 * 2. 工作线程 LlmClient.stream（带 tools）；文本 delta 走打字机 reveal；
 *    瞬时失败/空响应 → 按退避策略重试（最多 2 次）；
 * 3. 流结束：
 *    - 有 tool_calls → 回服务端线程逐个执行（结果带 [成功/失败] 标记并裁剪，
 *      重复调用守卫触发时注入提醒；追加 assistant(tool_calls) + tool(结果) 消息）→ 下一轮；
 *    - 无 tool_calls → 最终回复：写历史（historyKey 非空）+ 命令模式广播 / GUI "reply" 事件。
 * 4. 轮数超过 maxToolRounds → 把「已达最大行动步数」作为 tool 结果喂给模型做最后一轮总结。
 */
public final class AgentRuntime {
	/** 工作线程池（守护线程，与 {@link AiCompanionService} 共用约定）。 */
	private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "opencraft-agent-loop");
		t.setDaemon(true);
		return t;
	});

	/** 流式回复每次“上屏”的最小间隔（毫秒）。 */
	private static final long FLUSH_INTERVAL_MS = 80L;
	/** 流式进行中每次最多新显示的字符数（打字机步长，80ms 一次 ≈ 100 字符/秒，接近阅读速度）。 */
	private static final int LIVE_REVEAL_CHARS = 8;
	/** 收尾阶段最多再刷新的次数：流结束后剩余文本按自适应步长推完（约 5 秒内）。 */
	private static final int MAX_REVEAL_FLUSHES = 60;
	/** 单轮最多执行的工具调用数（超出部分直接以错误结果返回，防止模型一次连发太多调用撑爆上下文）。 */
	private static final int MAX_TOOLS_PER_ROUND = 6;
	/** ask_player 等待玩家回答的超时（毫秒），超时后按合理假设自动继续。 */
	private static final long ASK_TIMEOUT_MS = 90_000L;
	/** 核心工具名：向玩家提问（暂停/恢复，参考 dsh-tool-ask-user）。 */
	private static final String TOOL_ASK_PLAYER = "ask_player";
	/** 核心工具名：任务计划跟踪（参考 dsh-tool-todo）。 */
	private static final String TOOL_TASK_PLAN = "task_plan";

	/**
	 * 所有预设共享的基础人设（非配置，随代码内置）：简短友好 + 用玩家语言。
	 * 具体“怎么做事/何时用工具”由各预设的 personaPrompt 与插件提示词决定。
	 */
	private static final String BASE_PERSONA = """
			你是一个住在《我的世界》(Minecraft) 里的 AI 游戏助手，陪伴玩家一起冒险、建造、生存，
			像一位可靠又有点幽默的朋友。回答尽量简短（一般不超过 3~4 句话），用玩家使用的语言回复。""";

	/** 历史压缩的指令（拼在旧消息区段之后，要求模型只输出摘要正文）。 */
	private static final String COMPACT_INSTRUCTION = """
			请把上面这段你和玩家的历史聊天压缩成一份简短的记忆摘要（150 字以内）。
			保留：玩家的重要信息（名字、需求、约定、进度、待办）、你答应过的事、尚未完成的任务、关键坐标/物品。
			不要寒暄、不要逐条复述，只提炼要点。直接输出摘要正文，不要任何前缀或格式。""";

	/** 进行中的 loop 标记：按“助手绑定的方块”键控，保证同一助手同时只有一个 loop 在跑。 */
	private static final Map<GlobalPos, Boolean> RUNNING = new ConcurrentHashMap<>();

	/** 正在等待玩家回答的提问（按助手绑定方块键控；answer 或超时恢复时移除）。 */
	private static final Map<GlobalPos, PendingAsk> PENDING_ASKS = new ConcurrentHashMap<>();

	private AgentRuntime() {
	}

	/**
	 * 异步发起一次带工具循环的对话（GUI 模式或命令模式）。
	 *
	 * @param historyKey  对话记忆的键（null = 不写历史，如打招呼）
	 * @param guiBlockPos / guiDimension 非空 = GUI 模式（增量 "delta" 事件、结束 "reply" 事件，
	 *                    不广播世界聊天）；null = 命令模式（action bar + 世界聊天广播）
	 */
	public static void runAsync(ServerPlayer player, AiAssistant assistant,
	                            String question, GlobalPos historyKey,
	                            BlockPos guiBlockPos, ResourceKey<Level> guiDimension) {
		GlobalPos lockKey = historyKey != null ? historyKey : keyFor(assistant);
		if (RUNNING.putIfAbsent(lockKey, Boolean.TRUE) != null) {
			// 同一助手正在处理上一个问题：提示“正忙”，拒绝重复提问
			player.sendSystemMessage(Component.translatable("command.opencraft.ask.busy"));
			return;
		}
		boolean gui = guiBlockPos != null && guiDimension != null;
		AiBlockConfig config = assistant.getConfig();
		if (!config.isUsable()) {
			RUNNING.remove(lockKey);
			if (gui) {
				AiCompanionService.sendGuiEvent(player, guiBlockPos, guiDimension, "error",
						Component.translatable("command.opencraft.ask.no_config"));
			} else {
				player.sendSystemMessage(Component.translatable("command.opencraft.ask.no_config"));
			}
			return;
		}
		AgentDefinition agent = AgentRegistry.resolveAgent(config);
		MinecraftServer server = player.level().getServer();
		if (gui) {
			AiCompanionService.sendGuiEvent(player, guiBlockPos, guiDimension,
					"thinking", Component.empty());
		} else {
			player.displayClientMessage(Component.translatable("command.opencraft.ask.thinking"), true);
		}
		// 历史过长（超过 maxHistoryMessages×2）先异步压缩再开始本轮：保留更多记忆，防止只裁剪
		if (historyKey != null
				&& AiCompanionService.getHistory(historyKey).size() > config.maxHistoryMessages * 2L) {
			compactThenStart(server, player, config, agent, assistant, question,
					historyKey, lockKey, gui, guiBlockPos, guiDimension);
			return;
		}
		startLoop(player, config, agent, assistant, question,
				historyKey, lockKey, gui, guiBlockPos, guiDimension);
	}

	// ------------------------------------------------------------------
	// 历史压缩（参考 dsh-compaction-basic：最旧区段 → LLM 记忆摘要）
	// ------------------------------------------------------------------

	/**
	 * 异步把历史最旧区段压缩成记忆摘要，落地后开始新一轮对话。
	 * 压缩请求在工作线程执行；摘要替换/失败回退都在服务端线程落地（避免并发写历史）。
	 */
	private static void compactThenStart(MinecraftServer server, ServerPlayer player,
	                                     AiBlockConfig config, AgentDefinition agent,
	                                     AiAssistant assistant, String question,
	                                     GlobalPos historyKey, GlobalPos lockKey, boolean gui,
	                                     BlockPos guiBlockPos, ResourceKey<Level> guiDimension) {
		List<LlmClient.Message> live = AiCompanionService.getHistory(historyKey);
		int keep = Math.max(2, config.maxHistoryMessages);
		int oldCount = Math.max(0, live.size() - keep);
		List<LlmClient.Message> region = new ArrayList<>(live.subList(0, oldCount));
		CompletableFuture.runAsync(() -> {
			String summary = summarizeRegion(config, agent, region);
			runOnServer(server, () -> {
				List<LlmClient.Message> current = AiCompanionService.getHistory(historyKey);
				int drop = Math.min(Math.max(0, current.size() - keep), current.size());
				long regionChars = region.stream()
						.mapToLong(m -> m.content() == null ? 0 : m.content().length()).sum();
				if (summary != null && drop > 0 && summary.length() * 2 < regionChars) {
					// 压缩成功且确实变短：旧区段 → 一条记忆摘要（后续压缩会自然把旧摘要并入新摘要）
					current.subList(0, drop).clear();
					current.add(0, LlmClient.Message.user(
							"<compacted-summary>\n" + summary + "\n</compacted-summary>"));
					com.swaydy.opencraft.debug.DebugLog.log("history",
							"历史压缩：方块 {} 的 {} 条旧消息 → 1 条记忆摘要（{} 字符）",
							historyKey.pos().toShortString(), drop, summary.length());
				} else {
					// 压缩失败/未变短：退回直接裁剪（与旧行为一致）
					if (drop > 0) {
						current.subList(0, drop).clear();
					}
					com.swaydy.opencraft.debug.DebugLog.log("history",
							"历史裁剪（压缩不可用）：方块 {} 丢弃 {} 条最旧消息",
							historyKey.pos().toShortString(), drop);
				}
				startLoop(player, config, agent, assistant, question,
						historyKey, lockKey, gui, guiBlockPos, guiDimension);
			});
		}, EXECUTOR);
	}

	/**
	 * 在工作线程上把「最旧区段」压缩成摘要（一次非工具 LLM 调用，复用当前模型与 persona）。
	 * 返回 null 表示压缩不可用（请求失败/摘要为空/没有变短），调用方应退回裁剪。
	 */
	private static String summarizeRegion(AiBlockConfig config, AgentDefinition agent,
	                                      List<LlmClient.Message> region) {
		try {
			long regionChars = region.stream()
					.mapToLong(m -> m.content() == null ? 0 : m.content().length()).sum();
			if (regionChars <= 200) {
				return null; // 区段太小，不值得压缩
			}
			List<LlmClient.Message> messages = new ArrayList<>();
			messages.add(LlmClient.Message.system(buildPersona(config, agent)));
			messages.addAll(region);
			messages.add(LlmClient.Message.user(COMPACT_INSTRUCTION));
			LlmClient.Request request = new LlmClient.Request(
					config.baseUrl, config.apiKey, config.model,
					Math.min(0.5, config.temperature), messages, config.timeoutSeconds);
			LlmClient.Response resp = LlmClient.chat(request);
			if (!resp.ok()) {
				com.swaydy.opencraft.debug.DebugLog.log("history",
						"历史压缩请求失败: {}", resp.error());
				return null;
			}
			String summary = resp.content() == null ? "" : resp.content().trim();
			if (summary.isBlank() || summary.length() * 2 >= regionChars) {
				com.swaydy.opencraft.debug.DebugLog.log("history",
						"历史压缩结果未变短（{} → {} 字符），放弃压缩", regionChars, summary.length());
				return null;
			}
			return summary;
		} catch (Exception e) {
			com.swaydy.opencraft.debug.DebugLog.log("history", "历史压缩异常: {}", e.toString());
			return null;
		}
	}

	/** 启动新的一轮对话（历史已就绪）：写 user 消息 → 组 system+历史 → 跑第 0 轮。 */
	private static void startLoop(ServerPlayer player, AiBlockConfig config, AgentDefinition agent,
	                              AiAssistant assistant, String question, GlobalPos historyKey,
	                              GlobalPos lockKey, boolean gui,
	                              BlockPos guiBlockPos, ResourceKey<Level> guiDimension) {
		List<LlmClient.Message> history = historyKey == null ? new ArrayList<>()
				: new ArrayList<>(AiCompanionService.getHistory(historyKey));
		// 用户消息立即写入长期历史（与旧行为一致：historySize 同步增长，工具往返不写入）
		if (historyKey != null) {
			AiCompanionService.appendHistory(historyKey, LlmClient.Message.user(question));
		}
		history.add(LlmClient.Message.user(question));
		List<LlmClient.Message> messages = new ArrayList<>();
		messages.add(buildSystem(config, agent, player, assistant));
		messages.addAll(LlmClient.trimHistory(history, config.maxHistoryMessages));
		LoopContext ctx = new LoopContext(player, assistant, config, agent, messages,
				lockKey, historyKey, guiBlockPos, guiDimension, gui);
		runRound(ctx, 0);
	}

	// ------------------------------------------------------------------
	// 一轮循环（带重试）
	// ------------------------------------------------------------------

	private static void runRound(LoopContext ctx, int round) {
		if (!isAlive(ctx.assistant)) {
			// loop 中助手被送走/方块被拆：静默终止
			RUNNING.remove(ctx.lockKey);
			return;
		}
		// 已有任务计划：每轮把最新计划注入 system（保持 messages[0] 为单条 system 开头约束）
		if (ctx.planText != null && !ctx.planText.isBlank()) {
			LlmClient.Message system = buildSystemWithPlan(ctx);
			if (ctx.messages.isEmpty() || !"system".equals(ctx.messages.get(0).role())) {
				ctx.messages.add(0, system);
			} else {
				ctx.messages.set(0, system);
			}
		}
		// 插件工具 + 核心工具（ask_player / task_plan，随每个请求附加）
		List<com.google.gson.JsonObject> tools = new ArrayList<>(ctx.agent.toolsJson());
		tools.addAll(coreToolSchemas());
		LlmClient.Request request = new LlmClient.Request(
				ctx.config.baseUrl, ctx.config.apiKey, ctx.config.model, ctx.config.temperature,
				ctx.messages, ctx.config.timeoutSeconds, tools);
		com.swaydy.opencraft.debug.DebugLog.log("llm",
				"第 {} 轮请求 模型={} baseUrl={} 消息数={} 工具数={} 问题={}",
				round + 1, ctx.config.model, ctx.config.baseUrl, ctx.messages.size(),
				tools.size(),
				ctx.messages.get(ctx.messages.size() - 1).content() == null ? ""
						: ctx.messages.get(ctx.messages.size() - 1).content());
		streamWithRetry(ctx, round, request, false);
	}

	/**
	 * 带重试的流式请求。瞬时失败（未吐出任何字符前）或空响应（无内容无工具调用）时，
	 * 按 {@link LlmRetryPolicy} 指数退避 + 抖动重试（最多 2 次）；重试期间“正忙”锁保持持有。
	 *
	 * @param summaryRound true = 最后一轮总结（不带 tools，模型必须给文本回复）
	 */
	private static void streamWithRetry(LoopContext ctx, int round,
	                                    LlmClient.Request request, boolean summaryRound) {
		MinecraftServer server = ctx.player.level().getServer();
		LlmClient.StreamListener listener = new LlmClient.StreamListener() {
			private final StringBuilder buffer = new StringBuilder();
			private final long[] lastFlushAt = {0L};
			private final int[] revealed = {0};
			private final List<LlmClient.ToolCall> toolCalls = new ArrayList<>();

			@Override
			public void onDelta(String delta) {
				if (delta == null || delta.isEmpty()) {
					return;
				}
				buffer.append(delta);
				if (!summaryRound) {
					maybeReveal(false);
				}
			}

			@Override
			public void onToolCalls(List<LlmClient.ToolCall> calls) {
				if (calls != null) {
					toolCalls.addAll(calls);
				}
			}

			@Override
			public void onDone() {
				String full = buffer.toString();
				boolean hasTools = !toolCalls.isEmpty();
				if (hasTools && !summaryRound) {
					// 决策立即生效（不等打字机 reveal）：工具调用 → 回服务端线程执行并继续下一轮
					runOnServer(server, () -> {
						if (!isAlive(ctx.assistant)) {
							RUNNING.remove(ctx.lockKey);
							return;
						}
						showExecuting(ctx, round, toolCalls);
						// 追加 assistant(tool_calls) 消息
						ctx.messages.add(LlmClient.Message.assistant(full, toolCalls));
						// 核心工具：ask_player 优先处理——若提问有效则暂停等玩家回答（同批其余工具不执行）；
						// 提问参数无效则回显错误并照常执行其他工具（让模型下一轮修正）。
						boolean paused = false;
						LlmClient.ToolCall askCall = findAskCall(toolCalls);
						if (askCall != null) {
							String question = askQuestion(askCall);
							if (question == null || question.isBlank()) {
								ctx.messages.add(LlmClient.Message.tool(askCall.id(),
										ToolResultPruner.toModelText(TOOL_ASK_PLAYER, false,
												"请提供要问玩家的 question 参数（一个简短的确认问题）。")));
								ctx.messages.addAll(executeTools(ctx, toolCalls));
							} else {
								ctx.messages.add(LlmClient.Message.tool(askCall.id(),
										ToolResultPruner.toModelText(TOOL_ASK_PLAYER, true,
												"已向玩家提问，正在等待回复……")));
								pauseForAnswer(ctx, question, round + 1);
								paused = true;
							}
						} else {
							// 逐个执行工具（服务端线程），收集 tool 结果消息（含重复调用提醒）
							ctx.messages.addAll(executeTools(ctx, toolCalls));
						}
						if (paused) {
							// 已暂停等待玩家回答：不继续下一轮；“正忙”锁保持持有，由 answer/超时 恢复
							return;
						}
						// 交回工作线程发起下一轮
						CompletableFuture.runAsync(() -> {
							if (round + 1 >= ctx.agent.maxToolRounds()) {
								// 已达最大轮数：把“步数已尽”作为结果喂给模型做最后一轮总结
								ctx.messages.add(LlmClient.Message.tool("round-limit",
										"你已经达到了本次任务的最大行动步数（" + ctx.agent.maxToolRounds() + " 轮）。"
												+ "请立即停止行动，用一句简洁的话向玩家总结你已完成的事（不调用工具）。"));
								LlmClient.Request summaryRequest = new LlmClient.Request(
										ctx.config.baseUrl, ctx.config.apiKey, ctx.config.model,
										ctx.config.temperature, new ArrayList<>(ctx.messages),
										ctx.config.timeoutSeconds, null);
								streamWithRetry(ctx, round + 1, summaryRequest, true);
							} else {
								runRound(ctx, round + 1);
							}
						}, EXECUTOR);
					});
				} else if (full.isBlank() && canRetry()) {
					// 空响应（无内容也无工具调用）：视为可重试的退化完成（EMPTY_RESPONSE）
					scheduleRetry("空响应");
				} else if (summaryRound) {
					// 最后一轮总结：落账（只存最终文本）+ 释放“正忙”锁 + 收尾上屏
					runOnServer(server, () -> {
						RUNNING.remove(ctx.lockKey);
						if (ctx.historyKey != null && !full.isBlank()) {
							AiCompanionService.appendHistory(ctx.historyKey,
									LlmClient.Message.assistant(full));
						}
					});
					revealAndFinish(server, ctx, full);
				} else {
					// 无工具调用：最终回复
					// 2a) 先落账（服务端线程）：写历史（只存最终文本）
					runOnServer(server, () -> {
						if (ctx.historyKey != null && !full.isBlank()) {
							AiCompanionService.appendHistory(ctx.historyKey,
									LlmClient.Message.assistant(full));
						}
						// 落账即代表 LLM 工作完成：释放“正忙”锁。
						// 打字机 reveal/收尾（2b）只是展示，不应阻塞玩家下一条消息
						// （否则会出现“历史已 +1 但仍被 正忙 拒绝”的竞态）。
						RUNNING.remove(ctx.lockKey);
					});
					// 2b) 剩余文本打字机 reveal + 最终收尾（独立异步任务，不阻塞 SSE 读取线程）
					CompletableFuture.runAsync(() -> {
						while (revealed[0] < buffer.length()) {
							maybeReveal(true);
							if (revealed[0] >= buffer.length()) {
								break;
							}
							try {
								Thread.sleep(FLUSH_INTERVAL_MS);
							} catch (InterruptedException e) {
								Thread.currentThread().interrupt();
								break;
							}
						}
						runOnServer(server, () -> finishReply(ctx, full));
					}, EXECUTOR);
				}
			}

			@Override
			public void onError(String error) {
				String reason = error == null || error.isBlank() ? "未知错误" : error;
				// 尚未吐出任何字符且错误可重试：按退避策略重试（已吐字再重试会重复显示内容）
				if (buffer.length() == 0 && canRetry() && LlmRetryPolicy.retryable(reason)) {
					scheduleRetry(reason);
					return;
				}
				com.swaydy.opencraft.debug.DebugLog.log("llm",
						"LLM 请求失败（模型 {}）: {}", ctx.config.model, reason);
				runOnServer(server, () -> {
					RUNNING.remove(ctx.lockKey);
					if (ctx.gui) {
						AiCompanionService.sendGuiEvent(ctx.player, ctx.guiBlockPos, ctx.guiDimension,
								"error", Component.translatable("command.opencraft.ask.error", reason));
					} else {
						ctx.player.sendSystemMessage(
								Component.translatable("command.opencraft.ask.error", reason));
					}
				});
			}

			private boolean canRetry() {
				return ctx.llmRetries[0] < LlmRetryPolicy.MAX_RETRIES;
			}

			/** 按退避策略在 {@link #EXECUTOR} 上延迟重试同一轮请求（重试期间锁保持持有）。 */
			private void scheduleRetry(String reason) {
				int attempt = ctx.llmRetries[0] + 1;
				ctx.llmRetries[0] = attempt;
				long delay = LlmRetryPolicy.delayMs(attempt);
				com.swaydy.opencraft.debug.DebugLog.log("llm",
						"第 {} 轮请求失败（{}），{}ms 后第 {}/{} 次重试", round + 1, reason, delay,
						attempt, LlmRetryPolicy.MAX_RETRIES);
				CompletableFuture.runAsync(() -> {
					try {
						Thread.sleep(delay);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return; // 被中断：不再重试（进程/服务器正在停止，RUNNING 由 shutdown 清理）
					}
					runOnServer(server, () -> {
						if (!isAlive(ctx.assistant)) {
							RUNNING.remove(ctx.lockKey);
							return;
						}
						streamWithRetry(ctx, round, request, summaryRound);
					});
				}, EXECUTOR);
			}

			/** 按节流节奏推进“已显示”位置并推送快照（action bar 或 GUI "delta"）。 */
			private void maybeReveal(boolean finalizing) {
				long now = System.currentTimeMillis();
				if (!finalizing && now - lastFlushAt[0] < FLUSH_INTERVAL_MS) {
					return; // 时间未到：等下一个 delta 或收尾阶段再推
				}
				int len = buffer.length();
				if (revealed[0] >= len) {
					return;
				}
				int step = finalizing
						? Math.max(LIVE_REVEAL_CHARS,
								(len - revealed[0] + MAX_REVEAL_FLUSHES - 1) / MAX_REVEAL_FLUSHES)
						: LIVE_REVEAL_CHARS;
				int target = Math.min(len, revealed[0] + step);
				revealed[0] = target;
				lastFlushAt[0] = now;
				String snapshot = buffer.substring(0, target);
				runOnServer(server, () -> {
					if (ctx.gui) {
						AiCompanionService.sendGuiEvent(ctx.player, ctx.guiBlockPos, ctx.guiDimension,
								"delta", Component.literal(snapshot));
					} else {
						AiCompanionService.showStreamingText(ctx.player, snapshot);
					}
				});
			}
		};
		CompletableFuture.runAsync(() -> LlmClient.stream(request, listener), EXECUTOR);
	}

	/** 无本地 reveal 缓冲的收尾（仅用于轮数用尽后的最终总结）：小延迟后广播/GUI 收尾。 */
	private static void revealAndFinish(MinecraftServer server, LoopContext ctx, String full) {
		CompletableFuture.runAsync(() -> {
			// 最终总结短促停顿后整体上屏（该路径没有逐字缓冲）
			try {
				Thread.sleep(FLUSH_INTERVAL_MS * 3);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			runOnServer(server, () -> finishReply(ctx, full));
		}, EXECUTOR);
	}

	/** 最终回复收尾：GUI "reply" 事件 / 命令模式广播（空回复则静默清屏）。 */
	private static void finishReply(LoopContext ctx, String full) {
		if (full == null || full.isBlank()) {
			if (ctx.gui) {
				AiCompanionService.sendGuiEvent(ctx.player, ctx.guiBlockPos, ctx.guiDimension,
						"reply", Component.empty());
			} else {
				ctx.player.displayClientMessage(Component.empty(), true);
			}
			return;
		}
		if (ctx.gui) {
			AiCompanionService.finishGuiReply(ctx.player, ctx.guiBlockPos, ctx.guiDimension, full);
		} else {
			AiCompanionService.finishStreamReply(ctx.player, ctx.assistant, full);
		}
	}

	// ------------------------------------------------------------------
	// 工具执行
	// ------------------------------------------------------------------

	/**
	 * 在服务端线程执行每个工具，收集 tool 结果消息（异常捕获为 error 结果）。
	 * 结果统一以 [工具名 成功/失败] 标记开头并裁剪超长文本；每轮最多执行
	 * {@link #MAX_TOOLS_PER_ROUND} 个；重复调用守卫触发时追加一条 user 提醒消息。
	 */
	private static List<LlmClient.Message> executeTools(LoopContext ctx,
	                                                    List<LlmClient.ToolCall> calls) {
		List<LlmClient.Message> results = new ArrayList<>();
		Map<String, ToolDefinition> tools = ctx.agent.toolMap();
		int executed = 0;
		for (LlmClient.ToolCall call : calls) {
			String toolName = call.name() == null ? "" : call.name().trim();
			if (executed >= MAX_TOOLS_PER_ROUND) {
				results.add(LlmClient.Message.tool(call.id(), ToolResultPruner.toModelText(toolName, false,
						"本轮工具调用已达上限（" + MAX_TOOLS_PER_ROUND + " 个）。先观察以上结果再继续，"
								+ "不要一次发起太多调用。")));
				continue;
			}
			// 核心工具：task_plan（整单替换任务计划，不参与重复调用守卫）
			if (TOOL_TASK_PLAN.equals(toolName)) {
				TaskPlan plan = TaskPlan.fromJson(parseArgs(call.arguments()));
				ToolResult planResult = plan == null
						? ToolResult.error("task_plan 参数格式不对：需要 steps 数组，每项 {content, status}，"
								+ "status ∈ [pending|in_progress|completed]，content 非空且不重复，至少一步。")
						: ToolResult.ok("任务计划已更新：" + plan.summary() + "。之后每轮我都会看到这份计划，"
								+ "请按计划推进并及时更新状态。");
				executed++;
				com.swaydy.opencraft.debug.DebugLog.log("tool",
						"助手更新任务计划 → {}", plan == null ? "参数错误" : plan.summary());
				ctx.planText = plan == null ? ctx.planText : plan.format();
				results.add(LlmClient.Message.tool(call.id(),
						ToolResultPruner.toModelText(toolName, planResult.ok(), planResult.message())));
				continue;
			}
			ToolDefinition def = tools.get(toolName);
			ToolResult result;
			if (def == null) {
				result = ToolResult.error("未知工具 \"" + toolName + "\"。可用的工具有: "
						+ String.join(", ", tools.keySet()));
			} else {
				try {
					JsonObject args = parseArgs(call.arguments());
					if (args == null) {
						result = ToolResult.error("参数 JSON 无法解析: " + previewArgs(call.arguments())
								+ "；请重新给出正确的参数 JSON。");
					} else {
						ToolContext toolCtx = new ToolContext(ctx.player.level().getServer(),
								ctx.assistant, ctx.player, (ServerLevel) ctx.player.level());
						result = def.executor().execute(toolCtx, args);
					}
				} catch (Exception e) {
					OpenCraftMod.LOGGER.warn("[OpenCraft] 工具 {} 执行异常: {}",
							toolName, e.toString());
					result = ToolResult.error("内部错误: " + e.getClass().getSimpleName());
				}
			}
			executed++;
			com.swaydy.opencraft.debug.DebugLog.log("tool",
					"助手执行工具 {} 参数={} → 结果={}", toolName,
					call.arguments() == null ? "{}" : call.arguments(), result.message());
			results.add(LlmClient.Message.tool(call.id(),
					ToolResultPruner.toModelText(toolName, result.ok(), result.message())));
			OpenCraftMod.LOGGER.info("[OpenCraft] 助手为 {} 执行工具 {} → {}",
					ctx.player.getName().getString(), toolName, result.message());
			// 重复工具调用守卫：连续相同工具+相同参数达到阈值 → 注入提醒打断死循环
			String reminder = ctx.repeatGuard.observe(toolName, call.arguments());
			if (reminder != null) {
				com.swaydy.opencraft.debug.DebugLog.log("tool",
						"重复工具调用提醒：{} 已连续 {} 次相同调用", toolName, ctx.repeatGuard.currentCount());
				results.add(LlmClient.Message.user(reminder));
			}
		}
		return results;
	}

	/**
	 * 解析工具参数字符串；非法 JSON 或非对象时返回 null（工具自行校验缺参；
	 * null 表示“参数格式本身错了”，会把原文回显给模型让它自纠）。
	 */
	private static JsonObject parseArgs(String args) {
		if (args == null || args.isBlank()) {
			return new JsonObject();
		}
		try {
			var el = com.google.gson.JsonParser.parseString(args);
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

	// ------------------------------------------------------------------
	// 核心工具：ask_player（暂停/恢复）与 task_plan（任务计划）
	// ------------------------------------------------------------------

	/** 核心工具的 OpenAI tools schema（随每次请求附加在插件工具之后，所有预设可用）。 */
	private static List<com.google.gson.JsonObject> coreToolSchemas() {
		// ask_player：{ question: string(required), options: string[] }
		com.google.gson.JsonObject askProps = new com.google.gson.JsonObject();
		JsonObject qProp = new JsonObject();
		qProp.addProperty("type", "string");
		qProp.addProperty("description", "要问玩家的简短问题（中文，一句话）。");
		askProps.add("question", qProp);
		com.google.gson.JsonObject options = new com.google.gson.JsonObject();
		options.addProperty("type", "array");
		com.google.gson.JsonObject optItem = new com.google.gson.JsonObject();
		optItem.addProperty("type", "string");
		options.add("items", optItem);
		options.addProperty("description", "可选：给玩家的几个候选答案");
		askProps.add("options", options);
		// task_plan：{ steps: [{content, status}] }
		com.google.gson.JsonObject step = new com.google.gson.JsonObject();
		step.addProperty("type", "object");
		com.google.gson.JsonObject stepProps = new com.google.gson.JsonObject();
		JsonObject contentProp = new JsonObject();
		contentProp.addProperty("type", "string");
		contentProp.addProperty("description", "这一步要做什么");
		stepProps.add("content", contentProp);
		JsonObject statusProp = new JsonObject();
		statusProp.addProperty("type", "string");
		com.google.gson.JsonArray statusEnum = new com.google.gson.JsonArray();
		statusEnum.add("pending");
		statusEnum.add("in_progress");
		statusEnum.add("completed");
		statusProp.add("enum", statusEnum);
		statusProp.addProperty("description", "pending=待办, in_progress=进行中, completed=已完成");
		stepProps.add("status", statusProp);
		step.add("properties", stepProps);
		com.google.gson.JsonArray stepRequired = new com.google.gson.JsonArray();
		stepRequired.add("content");
		stepRequired.add("status");
		step.add("required", stepRequired);
		com.google.gson.JsonObject steps = new com.google.gson.JsonObject();
		steps.addProperty("type", "array");
		steps.add("items", step);
		steps.addProperty("description", "完整步骤列表，整单替换");
		com.google.gson.JsonObject planProps = new com.google.gson.JsonObject();
		planProps.add("steps", steps);
		return List.of(
				toolFn("ask_player",
						"在你无法确定该怎么做、或行动可能有破坏性/不可逆影响（如挖掘功能方块、目标不明确）时，"
								+ "向玩家提一个简短问题来确认。调用后对话会暂停，等玩家用 /opencraft answer 回答后继续；"
								+ "除非真需要确认，否则不要用。",
						askProps),
				toolFn("task_plan",
						"记录你当前多步任务的执行计划与进度。整单替换：每次调用发送完整列表。"
								+ "每完成一步就把该步标为 completed；只要任务没结束，至少保持一项 in_progress。"
								+ "简单一步任务不要用。",
						planProps));
	}

	/** 组装一个 {"type":"function","function":{name,description,parameters}} 的 tools 条目。 */
	private static com.google.gson.JsonObject toolFn(String name, String description,
	                                                 com.google.gson.JsonObject parameters) {
		com.google.gson.JsonObject fn = new com.google.gson.JsonObject();
		fn.addProperty("name", name);
		fn.addProperty("description", description);
		fn.add("parameters", parameters);
		com.google.gson.JsonObject schema = new com.google.gson.JsonObject();
		schema.addProperty("type", "function");
		schema.add("function", fn);
		return schema;
	}

	/** 从本批工具调用里找出 ask_player 调用（同批最多处理一个）。 */
	private static LlmClient.ToolCall findAskCall(List<LlmClient.ToolCall> calls) {
		for (LlmClient.ToolCall c : calls) {
			if (TOOL_ASK_PLAYER.equals(c.name() == null ? "" : c.name().trim())) {
				return c;
			}
		}
		return null;
	}

	/** 从 ask_player 参数里取问题文本；参数无效返回 null。 */
	private static String askQuestion(LlmClient.ToolCall call) {
		JsonObject args = parseArgs(call.arguments());
		if (args == null || !args.has("question") || !args.get("question").isJsonPrimitive()) {
			return null;
		}
		return args.get("question").getAsString().trim();
	}

	/** 向玩家提问并暂停循环：记录待回答状态、向玩家呈现、安排超时自动继续。 */
	private static void pauseForAnswer(LoopContext ctx, String question, int nextRound) {
		GlobalPos key = ctx.lockKey;
		if (PENDING_ASKS.containsKey(key)) {
			// 已经在等待上一个问题的回答：不应再重复（模型同批只发了一个 ask_player）
			return;
		}
		ctx.pausedByAsk = true;
		PENDING_ASKS.put(key, new PendingAsk(ctx, nextRound,
				(ctx.player.level() instanceof ServerLevel sl) ? sl.getServer() : ctx.player.level().getServer(),
				question));
		// 玩家侧呈现
		if (ctx.gui) {
			AiCompanionService.sendGuiEvent(ctx.player, ctx.guiBlockPos, ctx.guiDimension,
					"reply", Component.translatable("command.opencraft.ask.question", question));
		} else {
			AiCompanionService.speakAsAssistant((ServerLevel) ctx.player.level(), ctx.assistant,
					"想确认一下：" + question + "（回复 /opencraft answer <你的回答> 继续）");
		}
		com.swaydy.opencraft.debug.DebugLog.log("ask",
				"助手（方块 {}）向玩家提问，等待回答（超时 {}ms）：{}",
				key.pos().toShortString(), ASK_TIMEOUT_MS, question);
		// 超时自动继续（玩家已回答时 resumePending 取不到条目，自然 no-op）
		CompletableFuture.runAsync(() -> {
			try {
				Thread.sleep(ASK_TIMEOUT_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			timeoutPending(key);
		}, EXECUTOR);
	}

	/**
	 * 玩家通过 /opencraft answer 回答等待中的提问（服务端线程调用）。
	 * 返回 true 表示恢复成功；没有待回答的提问/非本人返回 false。
	 */
	public static boolean answer(ServerPlayer player, GlobalPos key, String text) {
		if (key == null || text == null || text.isBlank() || player == null) {
			return false;
		}
		PendingAsk pa = PENDING_ASKS.get(key);
		if (pa == null) {
			return false;
		}
		if (!pa.ctx.player.getUUID().equals(player.getUUID())) {
			return false; // 只有提问的助手的主人才有权限回答
		}
		if (PENDING_ASKS.remove(key, pa)) {
			continueAfterAnswer(pa, "（玩家回答）" + text.trim());
			return true;
		}
		return false;
	}

	/** 超时：玩家没在时限内回答，按合理假设继续（服务端线程可能在 worker 上触发）。 */
	private static void timeoutPending(GlobalPos key) {
		PendingAsk pa = PENDING_ASKS.remove(key);
		if (pa == null) {
			return;
		}
		runOnServer(pa.server, () -> continueAfterAnswer(pa,
				"（玩家在时限内没有回答「" + pa.question + "」——请基于最合理的假设继续行动，"
						+ "并在最终回复里说明你的假设。）"));
	}

	/** 把玩家回答写入对话并恢复循环（服务端线程）。 */
	private static void continueAfterAnswer(PendingAsk pa, String userMessage) {
		LoopContext ctx = pa.ctx;
		if (!isAlive(ctx.assistant)) {
			RUNNING.remove(ctx.lockKey);
			return;
		}
		ctx.pausedByAsk = false;
		ctx.messages.add(LlmClient.Message.user(userMessage));
		com.swaydy.opencraft.debug.DebugLog.log("ask",
				"助手（方块 {}）的提问已恢复：{}", ctx.lockKey.pos().toShortString(),
				userMessage.length() <= 60 ? userMessage : userMessage.substring(0, 60) + "…");
		// 恢复循环：达到步数上限走总结轮，否则正常下一轮
		if (pa.nextRound >= ctx.agent.maxToolRounds()) {
			ctx.messages.add(LlmClient.Message.tool("round-limit",
					"你已经达到了本次任务的最大行动步数（" + ctx.agent.maxToolRounds() + " 轮）。"
							+ "请立即停止行动，用一句简洁的话向玩家总结你已完成的事（不调用工具）。"));
			LlmClient.Request summaryRequest = new LlmClient.Request(
					ctx.config.baseUrl, ctx.config.apiKey, ctx.config.model,
					ctx.config.temperature, new ArrayList<>(ctx.messages),
					ctx.config.timeoutSeconds, null);
			streamWithRetry(ctx, pa.nextRound, summaryRequest, true);
		} else {
			runRound(ctx, pa.nextRound);
		}
	}

	// ------------------------------------------------------------------
	// 提示词组装
	// ------------------------------------------------------------------

	private static LlmClient.Message buildSystem(AiBlockConfig config, AgentDefinition agent,
	                                             ServerPlayer player, AiAssistant assistant) {
		// 单条 system：人设（基础 + 预设 persona + 名字） + 插件能力提示 + 游戏上下文 + 插件上下文
		StringBuilder sb = new StringBuilder();
		sb.append(buildPersona(config, agent));
		String frags = agent.systemPromptFragments();
		if (!frags.isBlank()) {
			sb.append('\n').append(frags);
		}
		sb.append('\n').append(AiCompanionService.buildGameContext(player));
		ToolContext ctx = new ToolContext(player.level().getServer(), assistant, player,
				(ServerLevel) player.level());
		String ctxFrags = agent.gameContextFragments(ctx);
		if (!ctxFrags.isBlank()) {
			sb.append('\n').append(ctxFrags);
		}
		return LlmClient.Message.system(sb.toString());
	}

	/** 在基础 system 上追加当前任务计划（供每轮重建 messages[0]，保持单条 system 开头约束）。 */
	private static LlmClient.Message buildSystemWithPlan(LoopContext ctx) {
		LlmClient.Message base = buildSystem(ctx.config, ctx.agent, ctx.player, ctx.assistant);
		if (ctx.planText == null || ctx.planText.isBlank()) {
			return base;
		}
		return LlmClient.Message.system(base.content() + "\n\n【当前任务计划】\n" + ctx.planText);
	}

	/**
	 * 组装“人设 + 名字”的 system 文本（供对话与打招呼共用）：
	 * 基础人设 + 预设 personaPrompt + 【名字】指令。不再有玩家可编辑的系统提示词——
	 * 人设完全由 Agent 预设决定。
	 */
	public static String buildPersona(AiBlockConfig config, AgentDefinition agent) {
		StringBuilder sb = new StringBuilder();
		sb.append(BASE_PERSONA);
		if (agent != null && agent.personaPrompt() != null && !agent.personaPrompt().isBlank()) {
			sb.append('\n').append(agent.personaPrompt());
		}
		sb.append("\n\n【名字】你的名字是 ").append(config.effectiveName())
				.append("，请用这个名字自称，不要使用其他名字。");
		return sb.toString();
	}

	// ------------------------------------------------------------------
	// 杂项
	// ------------------------------------------------------------------

	/** 展示“正在执行”：第 N/M 步 + 本轮调用的工具名（GUI "thinking" 事件 / 命令模式系统消息）。 */
	private static void showExecuting(LoopContext ctx, int round, List<LlmClient.ToolCall> calls) {
		String names = calls.stream().map(LlmClient.ToolCall::name)
				.filter(n -> n != null && !n.isBlank())
				.distinct().limit(3).collect(Collectors.joining(", "));
		Component msg = Component.translatable("command.opencraft.agent.executing",
				round + 1, ctx.agent.maxToolRounds(), names);
		if (ctx.gui) {
			AiCompanionService.sendGuiEvent(ctx.player, ctx.guiBlockPos, ctx.guiDimension,
					"thinking", msg);
		} else {
			ctx.player.sendSystemMessage(msg);
		}
	}

	private static boolean isAlive(AiAssistant assistant) {
		return assistant != null && !assistant.isRemoved() && assistant.isAlive()
				&& assistant.getConfigBlock() != null;
	}

	private static GlobalPos keyFor(AiAssistant assistant) {
		GlobalPos block = assistant.getConfigBlock();
		return block != null ? block
				: GlobalPos.of(assistant.level().dimension(), assistant.blockPosition());
	}

	private static void runOnServer(MinecraftServer server, Runnable task) {
		try {
			server.executeIfPossible(task);
		} catch (Exception e) {
			OpenCraftMod.LOGGER.warn("[OpenCraft] 无法调度到服务端线程: {}", e.toString());
		}
	}

	/** 单次提问循环的共享状态：跨轮传递（消息列表、正忙锁、重复守卫、重试计数）。 */
	private static final class LoopContext {
		final ServerPlayer player;
		final AiAssistant assistant;
		final AiBlockConfig config;
		final AgentDefinition agent;
		final List<LlmClient.Message> messages;
		final GlobalPos lockKey;
		final GlobalPos historyKey;
		final BlockPos guiBlockPos;
		final ResourceKey<Level> guiDimension;
		final boolean gui;
		/** 重复工具调用守卫：每轮观察工具调用，撞阈值注入提醒（同一次提问内累计）。 */
		final RepeatToolGuard repeatGuard = new RepeatToolGuard();
		/** 本轮请求的重试计数（跨重试调度保持，最多 {@link LlmRetryPolicy#MAX_RETRIES} 次）。 */
		final int[] llmRetries = {0};
		/** 模型通过 task_plan 维护的当前任务计划（格式化文本），null = 无计划。 */
		String planText = null;
		/** ask_player 已暂停等待玩家回答（本批工具停止继续，等 answer/超时 恢复）。 */
		boolean pausedByAsk = false;

		LoopContext(ServerPlayer player, AiAssistant assistant, AiBlockConfig config,
		            AgentDefinition agent, List<LlmClient.Message> messages,
		            GlobalPos lockKey, GlobalPos historyKey,
		            BlockPos guiBlockPos, ResourceKey<Level> guiDimension, boolean gui) {
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
		}
	}

	/** 一次待回答的提问：持有恢复循环所需的全部状态。 */
	private static final class PendingAsk {
		final LoopContext ctx;
		final int nextRound; // 玩家回答后从第几轮恢复（= 提问那一轮的下一轮）
		final MinecraftServer server;
		final String question;

		PendingAsk(LoopContext ctx, int nextRound, MinecraftServer server, String question) {
			this.ctx = ctx;
			this.nextRound = nextRound;
			this.server = server;
			this.question = question;
		}
	}

	/** 服务器停止时关闭线程池并清理运行标记（由 {@link AiCompanionService#init()} 的钩子一并调用）。 */
	public static void shutdown() {
		RUNNING.clear();
		PENDING_ASKS.clear();
		EXECUTOR.shutdown();
	}
}
