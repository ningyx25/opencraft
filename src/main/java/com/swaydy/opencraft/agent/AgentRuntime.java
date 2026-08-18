package com.swaydy.opencraft.agent;

import com.google.gson.JsonObject;
import com.swaydy.opencraft.OpenCraftMod;
import com.swaydy.opencraft.assistant.AiAssistant;
import com.swaydy.opencraft.ai.AiBlockConfig;
import com.swaydy.opencraft.ai.AiCompanionService;
import com.swaydy.opencraft.ai.LlmClient;
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

/**
 * Agentic loop 执行器：驱动 AI 助手走“观察 → 决策 → 行动 → 再观察”的循环。
 *
 * 线程模型（沿用现有约定）：
 * - HTTP/SSE 读取在工作线程（{@link #EXECUTOR}），流式增量在那边按打字机节奏 reveal；
 * - 工具执行、历史写入、聊天广播一律 {@code server.executeIfPossible} 回服务端线程；
 * - 工具执行完成后由服务端线程把「继续下一轮请求」的任务交回工作线程池；
 * - 长任务（寻路、挖掘）不在工具调用里阻塞——工具只下达指令（设置任务/Goal），立即返回；
 *   模型通过后续 {@code look_around} 观察结果。
 *
 * 每轮流程：
 * 1. 组装消息：system（预设 persona + 插件提示词 + 游戏上下文 + 插件上下文）+ 历史 + user；
 * 2. 工作线程 LlmClient.stream（带 tools）；文本 delta 走打字机 reveal；
 * 3. 流结束：
 *    - 有 tool_calls → 回服务端线程逐个执行（追加 assistant(tool_calls) + tool(结果) 消息）→ 下一轮；
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

	/**
	 * 所有预设共享的基础人设（非配置，随代码内置）：简短友好 + 用玩家语言。
	 * 具体“怎么做事/何时用工具”由各预设的 personaPrompt 与插件提示词决定。
	 */
	private static final String BASE_PERSONA = """
			你是一个住在《我的世界》(Minecraft) 里的 AI 游戏助手，陪伴玩家一起冒险、建造、生存，
			像一位可靠又有点幽默的朋友。回答尽量简短（一般不超过 3~4 句话），用玩家使用的语言回复。""";

	/** 进行中的 loop 标记：按“助手绑定的方块”键控，保证同一助手同时只有一个 loop 在跑。 */
	private static final Map<GlobalPos, Boolean> RUNNING = new ConcurrentHashMap<>();

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
		// 组装首轮消息：单条 system 开头（vLLM/Qwen 约束），历史只存 user/assistant 最终文本
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

		MinecraftServer server = player.level().getServer();
		if (gui) {
			AiCompanionService.sendGuiEvent(player, guiBlockPos, guiDimension,
					"thinking", Component.empty());
		} else {
			player.displayClientMessage(Component.translatable("command.opencraft.ask.thinking"), true);
		}

		runRound(player, assistant, config, agent, messages, history, lockKey, historyKey,
				guiBlockPos, guiDimension, gui, 0);
	}

	// ------------------------------------------------------------------
	// 一轮循环
	// ------------------------------------------------------------------

	private static void runRound(ServerPlayer player, AiAssistant assistant,
	                             AiBlockConfig config, AgentDefinition agent,
	                             List<LlmClient.Message> messages, List<LlmClient.Message> history,
	                             GlobalPos lockKey, GlobalPos historyKey,
	                             BlockPos guiBlockPos, ResourceKey<Level> guiDimension,
	                             boolean gui, int round) {
		MinecraftServer server = player.level().getServer();
		if (!isAlive(assistant)) {
			// loop 中助手被送走/方块被拆：静默终止
			RUNNING.remove(lockKey);
			return;
		}
		LlmClient.Request request = new LlmClient.Request(
				config.baseUrl, config.apiKey, config.model, config.temperature,
				messages, config.timeoutSeconds, agent.toolsJson());
		com.swaydy.opencraft.debug.DebugLog.log("llm",
				"第 {} 轮请求 模型={} baseUrl={} 消息数={} 工具数={} 问题={}",
				round + 1, config.model, config.baseUrl, messages.size(),
				agent.toolMap().size(),
				messages.get(messages.size() - 1).content() == null ? ""
						: messages.get(messages.size() - 1).content());

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
				maybeReveal(false);
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
				// 1) 决策立即生效（不等打字机 reveal）：
				//    工具调用 → 回服务端线程执行并继续下一轮；
				//    无工具 → 先把最终文本写入历史（落账），再异步收尾上屏。
				if (hasTools) {
					// 先展示一条“正在执行”状态
					runOnServer(server, () -> {
						if (!isAlive(assistant)) {
							RUNNING.remove(lockKey);
							return;
						}
						showExecuting(player, gui, guiBlockPos, guiDimension, toolCalls);
						// 追加 assistant(tool_calls) 消息
						messages.add(LlmClient.Message.assistant(full, toolCalls));
						// 逐个执行工具（服务端线程），收集 tool 结果消息
						List<LlmClient.Message> toolMessages = executeTools(player, assistant, config, agent, toolCalls);
						messages.addAll(toolMessages);
						// 交回工作线程发起下一轮
						CompletableFuture.runAsync(() -> {
							if (round + 1 >= agent.maxToolRounds()) {
								// 已达最大轮数：把“步数已尽”作为结果喂给模型做最后一轮总结
								messages.add(LlmClient.Message.tool("round-limit",
										"你已经达到了本次任务的最大行动步数（" + agent.maxToolRounds() + " 轮）。"
												+ "请立即停止行动，用一句简洁的话向玩家总结你已完成的事（不调用工具）。"));
								finishWithoutTools(server, player, assistant, messages, config, agent,
										lockKey, historyKey, guiBlockPos, guiDimension, gui);
							} else {
								runRound(player, assistant, config, agent, messages, history, lockKey,
										historyKey, guiBlockPos, guiDimension, gui, round + 1);
							}
						}, EXECUTOR);
					});
				} else {
					// 无工具调用：最终回复
					// 2a) 先落账（服务端线程）：写历史（只存最终文本）
					runOnServer(server, () -> {
						if (historyKey != null && !full.isBlank()) {
							AiCompanionService.appendHistory(historyKey,
									LlmClient.Message.assistant(full));
						}
						// 落账即代表 LLM 工作完成：释放“正忙”锁。
						// 打字机 reveal/收尾（2b）只是展示，不应阻塞玩家下一条消息
						// （否则会出现“历史已 +1 但仍被 正忙 拒绝”的竞态）。
						RUNNING.remove(lockKey);
					});
					// 2b) 剩余文本打字机 reveal + 最终收尾（独立异步任务，不阻塞 SSE 读取线程）
					CompletableFuture.runAsync(() -> {
						// 继续把剩余文本按节流节奏推完（不打断 SSE 读取线程）
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
						// 收尾（服务端线程）：GUI "reply" / 命令模式广播
						runOnServer(server, () -> {
							if (full == null || full.isBlank()) {
								if (gui) {
									AiCompanionService.sendGuiEvent(player, guiBlockPos, guiDimension,
											"reply", Component.empty());
								} else {
									player.displayClientMessage(Component.empty(), true);
								}
								return;
							}
							if (gui) {
								AiCompanionService.finishGuiReply(player, guiBlockPos, guiDimension, full);
							} else {
								AiCompanionService.finishStreamReply(player, assistant, full);
							}
						});
					}, EXECUTOR);
				}
			}

			@Override
			public void onError(String error) {
				String reason = error == null || error.isBlank() ? "未知错误" : error;
				com.swaydy.opencraft.debug.DebugLog.log("llm",
						"LLM 请求失败（模型 {}）: {}", config.model, reason);
				runOnServer(server, () -> {
					RUNNING.remove(lockKey);
					if (gui) {
						AiCompanionService.sendGuiEvent(player, guiBlockPos, guiDimension, "error",
								Component.translatable("command.opencraft.ask.error", reason));
					} else {
						player.sendSystemMessage(
								Component.translatable("command.opencraft.ask.error", reason));
					}
				});
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
					if (gui) {
						AiCompanionService.sendGuiEvent(player, guiBlockPos, guiDimension,
								"delta", Component.literal(snapshot));
					} else {
						AiCompanionService.showStreamingText(player, snapshot);
					}
				});
			}
		};
		CompletableFuture.runAsync(() -> LlmClient.stream(request, listener), EXECUTOR);
	}

	/** 有工具调用时的最终收尾：把剩余文本 reveal 完，然后以最终总结广播/GUI 收尾。 */
	private static void finishWithoutTools(MinecraftServer server, ServerPlayer player,
	                                        AiAssistant assistant, List<LlmClient.Message> messages,
	                                        AiBlockConfig config, AgentDefinition agent,
	                                        GlobalPos lockKey, GlobalPos historyKey,
	                                        BlockPos guiBlockPos, ResourceKey<Level> guiDimension,
	                                        boolean gui) {
		// 发起不带 tools 的最后一轮总结（模型必须给文本回复）
		LlmClient.Request request = new LlmClient.Request(
				config.baseUrl, config.apiKey, config.model, config.temperature,
				new ArrayList<>(messages), config.timeoutSeconds, null);
		LlmClient.StreamListener listener = new LlmClient.StreamListener() {
			private final StringBuilder buffer = new StringBuilder();

			@Override
			public void onDelta(String delta) {
				if (delta != null && !delta.isEmpty()) {
					buffer.append(delta);
				}
			}

			@Override
			public void onDone() {
				String summary = buffer.toString();
				runOnServer(server, () -> {
					RUNNING.remove(lockKey);
					if (historyKey != null && !summary.isBlank()) {
						AiCompanionService.appendHistory(lockKey,
								LlmClient.Message.assistant(summary));
					}
				});
				revealAndFinish(server, player, assistant, summary, gui, guiBlockPos, guiDimension,
						lockKey);
			}

			@Override
			public void onError(String error) {
				runOnServer(server, () -> {
					RUNNING.remove(lockKey);
					if (gui) {
						AiCompanionService.sendGuiEvent(player, guiBlockPos, guiDimension, "error",
								Component.translatable("command.opencraft.ask.error",
										error == null ? "未知错误" : error));
					} else {
						player.sendSystemMessage(Component.translatable("command.opencraft.ask.error",
								error == null ? "未知错误" : error));
					}
				});
			}

			@Override
			public void onToolCalls(List<LlmClient.ToolCall> calls) {
				// 最后一轮不带 tools，模型不应再调用；忽略
			}
		};
		CompletableFuture.runAsync(() -> LlmClient.stream(request, listener), EXECUTOR);
	}

	/** 无本地 reveal 缓冲的收尾（仅用于轮数用尽后的最终总结）：小延迟后广播/GUI 收尾。 */
	private static void revealAndFinish(MinecraftServer server, ServerPlayer player,
	                                     AiAssistant assistant, String full,
	                                     boolean gui, BlockPos guiBlockPos, ResourceKey<Level> guiDimension,
	                                     GlobalPos lockKey) {
		CompletableFuture.runAsync(() -> {
			// 最终总结短促停顿后整体上屏（该路径没有逐字缓冲）
			try {
				Thread.sleep(FLUSH_INTERVAL_MS * 3);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			runOnServer(server, () -> {
				RUNNING.remove(lockKey);
				if (full == null || full.isBlank()) {
					if (gui) {
						AiCompanionService.sendGuiEvent(player, guiBlockPos, guiDimension,
								"reply", Component.empty());
					} else {
						player.displayClientMessage(Component.empty(), true);
					}
					return;
				}
				if (gui) {
					AiCompanionService.finishGuiReply(player, guiBlockPos, guiDimension, full);
				} else {
					AiCompanionService.finishStreamReply(player, assistant, full);
				}
			});
		}, EXECUTOR);
	}

	// ------------------------------------------------------------------
	// 工具执行
	// ------------------------------------------------------------------

	/** 在服务端线程执行每个工具，收集 tool 结果消息（异常捕获为 error 结果）。 */
	private static List<LlmClient.Message> executeTools(ServerPlayer player,
	                                                     AiAssistant assistant,
	                                                     AiBlockConfig config, AgentDefinition agent,
	                                                     List<LlmClient.ToolCall> calls) {
		List<LlmClient.Message> results = new ArrayList<>();
		Map<String, ToolDefinition> tools = agent.toolMap();
		for (LlmClient.ToolCall call : calls) {
			String toolName = call.name() == null ? "" : call.name().trim();
			ToolDefinition def = tools.get(toolName);
			ToolResult result;
			if (def == null) {
				result = ToolResult.error("未知工具 \"" + toolName + "\"。可用的工具有: "
						+ String.join(", ", tools.keySet()));
			} else {
				try {
					JsonObject args = parseArgs(call.arguments());
					ToolContext ctx = new ToolContext(player.level().getServer(), assistant, player,
							(ServerLevel) player.level());
					result = def.executor().execute(ctx, args);
				} catch (Exception e) {
					OpenCraftMod.LOGGER.warn("[OpenCraft] 工具 {} 执行异常: {}",
							toolName, e.toString());
					result = ToolResult.error("内部错误: " + e.getClass().getSimpleName());
				}
			}
			com.swaydy.opencraft.debug.DebugLog.log("tool",
					"助手执行工具 {} 参数={} → 结果={}", toolName,
					call.arguments() == null ? "{}" : call.arguments(), result.message());
			results.add(LlmClient.Message.tool(call.id(), result.message()));
			OpenCraftMod.LOGGER.info("[OpenCraft] 助手为 {} 执行工具 {} → {}",
					player.getName().getString(), toolName, result.message());
		}
		return results;
	}

	/** 解析工具参数字符串；非法 JSON 时返回空对象（工具自行校验缺参）。 */
	private static JsonObject parseArgs(String args) {
		if (args == null || args.isBlank()) {
			return new JsonObject();
		}
		try {
			var el = com.google.gson.JsonParser.parseString(args);
			return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
		} catch (Exception e) {
			return new JsonObject();
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

	private static void showExecuting(ServerPlayer player, boolean gui,
	                                  BlockPos guiBlockPos, ResourceKey<Level> guiDimension,
	                                  List<LlmClient.ToolCall> calls) {
		if (gui) {
			AiCompanionService.sendGuiEvent(player, guiBlockPos, guiDimension, "thinking",
					Component.translatable("command.opencraft.agent.executing"));
		} else {
			player.sendSystemMessage(Component.translatable("command.opencraft.agent.executing"));
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

	/** 服务器停止时关闭线程池（由 {@link AiCompanionService#init()} 的钩子一并调用）。 */
	public static void shutdown() {
		EXECUTOR.shutdown();
	}
}